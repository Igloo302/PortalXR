package org.a2ui.compose.rendering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.a2ui.compose.data.*
import org.a2ui.compose.data.ChildList.ArrayChildList
import org.a2ui.compose.error.*

sealed class A2UIRendererState {
    object Idle : A2UIRendererState()
    object Loading : A2UIRendererState()
    data class Error(val message: String, val error: A2UIError? = null) : A2UIRendererState()
}

data class SavedRendererState(
    val surfaces: Map<String, SurfaceContext>,
    val dataModels: Map<String, Map<String, Any?>>,
    val components: Map<String, Map<String, Component>>
)

class A2UIRenderer(
    private val logger: A2UILogger = DefaultLogger(),
    private val errorHandler: A2UIErrorHandler? = null
) {
    private val dataModelProcessor = DataModelProcessor()
    val registry = ComponentRegistry(this)
    private val surfaces = mutableStateMapOf<String, SurfaceContext>()
    private val surfaceComponents = mutableStateMapOf<String, SnapshotStateMap<String, Component>>()
    private val surfaceStates = mutableStateMapOf<String, A2UIRendererState>()
    private val missingComponentWarnings = linkedSetOf<String>()

    private val _actionHandler = MutableStateFlow<ActionHandler?>(null)
    val actionHandler: StateFlow<ActionHandler?>
        get() = _actionHandler.asStateFlow()

    private val _errors = mutableStateListOf<A2UIError>()
    val errors: List<A2UIError> get() = _errors.toList()

    companion object {
        const val MAX_MESSAGE_SIZE = 1 * 1024 * 1024  // 1MB
        const val MAX_COMPONENTS_PER_SURFACE = 1000
        const val MAX_SURFACES = 50
        const val MAX_ERROR_COUNT = 100
        val ALLOWED_URL_SCHEMES = setOf("https://", "http://", "mailto:", "tel:", "sms:")

        val Saver: Saver<A2UIRenderer, SavedRendererState> = Saver(
            save = { renderer -> renderer.saveState() },
            restore = { savedState ->
                A2UIRenderer().apply { restoreState(savedState) }
            }
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(Any::class, AnySerializer)
        }
    }

    private val surfaceChanges = mutableStateMapOf<String, MutableStateFlow<Unit>>()

    fun setActionHandler(handler: ActionHandler?) {
        _actionHandler.value = handler
    }

    fun processMessage(message: String): Result<Unit> {
        android.util.Log.d("A2UI", "processMessage called, length=${message.length}")
        return try {
            if (message.isBlank()) {
                val error = A2UIError.ParseError("Empty message", message)
                handleError(error, "unknown")
                return Result.failure(IllegalArgumentException("Message cannot be empty"))
            }

            if (message.length > MAX_MESSAGE_SIZE) {
                val error = A2UIError.ParseError("Message too large: ${message.length} bytes")
                handleError(error, "unknown")
                return Result.failure(IllegalArgumentException("Message exceeds size limit"))
            }

            // Parse JSON once, reuse for type detection and deserialization
            val jsonObj = try {
                json.parseToJsonElement(message).jsonObject
            } catch (e: Exception) {
                android.util.Log.e("A2UI", "Failed to parse JSON: ${e.message}")
                val error = A2UIError.ParseError("Invalid JSON: ${e.message}", message)
                handleError(error, "unknown")
                return Result.failure(e)
            }

            android.util.Log.d("A2UI", "JSON parsed, keys: ${jsonObj.keys}")

            // Extract surfaceId from various message formats (v0.8 and v0.9)
            val surfaceId = jsonObj["createSurface"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj["updateComponents"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj["updateDataModel"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj["deleteSurface"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj["surfaceUpdate"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj["beginRendering"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: "main"

            android.util.Log.d("A2UI", "Detected surfaceId: $surfaceId")

            // Handle v0.8 format messages
            when {
                "surfaceUpdate" in jsonObj -> {
                    android.util.Log.d("A2UI", "Handling v0.8 surfaceUpdate")
                    handleV08SurfaceUpdate(jsonObj["surfaceUpdate"]!!.jsonObject)
                    surfaceStates[surfaceId] = A2UIRendererState.Idle
                    surfaceChanges[surfaceId]?.value = Unit
                    Result.success(Unit)
                }
                "beginRendering" in jsonObj -> {
                    android.util.Log.d("A2UI", "Handling v0.8 beginRendering")
                    handleV08BeginRendering(jsonObj["beginRendering"]!!.jsonObject)
                    surfaceStates[surfaceId] = A2UIRendererState.Idle
                    surfaceChanges[surfaceId]?.value = Unit
                    Result.success(Unit)
                }
                else -> {
                    android.util.Log.d("A2UI", "Trying v0.9 format")
                    // v0.9 format
                    val a2uiMessage = try {
                        when {
                            "createSurface" in jsonObj -> json.decodeFromJsonElement<CreateSurfaceMessage>(jsonObj)
                            "updateComponents" in jsonObj -> json.decodeFromJsonElement<UpdateComponentsMessage>(jsonObj)
                            "updateDataModel" in jsonObj -> json.decodeFromJsonElement<UpdateDataModelMessage>(jsonObj)
                            "deleteSurface" in jsonObj -> json.decodeFromJsonElement<DeleteSurfaceMessage>(jsonObj)
                            else -> throw SerializationException("Unknown message type: no recognized key found")
                        }
                    } catch (e: SerializationException) {
                        android.util.Log.e("A2UI", "v0.9 decode failed: ${e.message}")
                        val error = A2UIError.ParseError("Invalid JSON format: ${e.message}", message)
                        handleError(error, surfaceId)
                        return Result.failure(e)
                    }

                    // Batch all snapshot state mutations into a single atomic update
                    // so Compose sees one recomposition instead of N separate ones
                    Snapshot.withMutableSnapshot {
                        when (a2uiMessage) {
                            is CreateSurfaceMessage -> handleCreateSurface(a2uiMessage.createSurface)
                            is UpdateComponentsMessage -> handleUpdateComponents(a2uiMessage.updateComponents)
                            is UpdateDataModelMessage -> handleUpdateDataModel(a2uiMessage.updateDataModel)
                            is DeleteSurfaceMessage -> handleDeleteSurface(a2uiMessage.deleteSurface)
                        }
                        surfaceStates[surfaceId] = A2UIRendererState.Idle
                    }
                    surfaceChanges[surfaceId]?.value = Unit
                    Result.success(Unit)
                }
            }
        } catch (e: IllegalArgumentException) {
            android.util.Log.e("A2UI", "Invalid argument: ${e.message}", e)
            logger.log(A2UILogLevel.ERROR, "Invalid argument: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            logger.log(A2UILogLevel.ERROR, "Error processing message: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Handle v0.8 surfaceUpdate message
     */
    private fun handleV08SurfaceUpdate(surfaceUpdate: JsonObject) {
        val surfaceId = surfaceUpdate["surfaceId"]?.jsonPrimitive?.content ?: "main"
        val componentsArray = (surfaceUpdate["components"] as? JsonArray) ?: return

        // Create surface if not exists
        if (!surfaces.containsKey(surfaceId)) {
            handleCreateSurface(CreateSurface(surfaceId, "default", null, false))
        }

        val componentMap = surfaceComponents[surfaceId] ?: return

        componentsArray.forEach { element ->
            val compObj = element as? JsonObject ?: return@forEach
            val id = compObj["id"]?.jsonPrimitive?.content ?: return@forEach
            try {
                val component = parseV08Component(compObj)
                componentMap[id] = component
            } catch (e: Exception) {
                logger.log(A2UILogLevel.ERROR, "Failed to parse component $id: ${e.message}")
                android.util.Log.e("A2UI", "Failed to parse component $id", e)
            }
        }

        logger.log(A2UILogLevel.DEBUG, "v0.8 surfaceUpdate: $surfaceId, ${componentsArray.size} components")
    }

    /**
     * Handle v0.8 beginRendering message
     */
    private fun handleV08BeginRendering(beginRendering: JsonObject) {
        val surfaceId = beginRendering["surfaceId"]?.jsonPrimitive?.content ?: "main"
        val rootId = beginRendering["root"]?.jsonPrimitive?.content ?: "root"

        // Ensure surface exists
        if (!surfaces.containsKey(surfaceId)) {
            handleCreateSurface(CreateSurface(surfaceId, "default", null, false))
        }

        // Set the root component ID for this surface
        surfaces[surfaceId]?.rootComponentId = rootId

        logger.log(A2UILogLevel.DEBUG, "v0.8 beginRendering: $surfaceId, root=$rootId")
    }

    /**
     * Parse v0.8 component format into our Component model
     */
    private fun parseV08Component(compObj: JsonObject): Component {
        val id = compObj["id"]?.jsonPrimitive?.content ?: ""
        val componentObj = compObj["component"]?.jsonObject ?: return Component(id = id, component = "Text")

        // Extract component type (e.g., "Text", "Column", "Button")
        val componentType = componentObj.keys.firstOrNull() ?: "Text"
        val props = componentObj[componentType]?.jsonObject ?: return Component(id = id, component = componentType)

        // Handle action property (official v0.8 spec), onPress, and onTap for Button actions
        val action = props["action"]?.let { parseActionObject(it) }
            ?: props["onPress"]?.let { parseAction(it) }
            ?: props["onTap"]?.let { parseOnTapAction(it) }

        return Component(
            id = id,
            component = componentType,
            text = props["text"]?.let { parseDynamicValue(it) },
            url = props["url"]?.let { parseDynamicValue(it) },
            children = props["children"]?.let { parseChildren(it) },
            child = props["child"]?.jsonPrimitive?.content,
            action = action,
            label = props["label"]?.let { parseDynamicValue(it) },
            variant = props["variant"]?.jsonPrimitive?.content,
            usageHint = props["usageHint"]?.jsonPrimitive?.content,
            placeholder = props["placeholder"]?.let { parseDynamicValue(it) },
            value = props["value"]?.let { parseAnyDynamicValue(it) },
            justify = props["justify"]?.jsonPrimitive?.content,
            align = props["align"]?.jsonPrimitive?.content,
            direction = props["direction"]?.jsonPrimitive?.content,
            axis = props["axis"]?.jsonPrimitive?.content,
            weight = props["weight"]?.jsonPrimitive?.let { it.longOrNull?.toInt() },
            min = props["min"]?.jsonPrimitive?.doubleOrNull,
            max = props["max"]?.jsonPrimitive?.doubleOrNull,
            step = props["step"]?.jsonPrimitive?.doubleOrNull,
            options = props["options"]?.let { parseOptions(it) },
            multiple = props["multiple"]?.jsonPrimitive?.booleanOrNull,
            fit = props["fit"]?.jsonPrimitive?.content,
            trigger = props["trigger"]?.jsonPrimitive?.content,
            content = props["content"]?.jsonPrimitive?.content,
            primary = props["primary"]?.jsonPrimitive?.booleanOrNull,
            description = props["description"]?.let { parseDynamicValue(it) },
            name = props["name"]?.let { parseDynamicValue(it) }
        )
    }

    /**
     * Parse official v0.8 action object: {"name": "action_name", "context": [...]}
     */
    private fun parseActionObject(element: JsonElement): Action? {
        if (element !is JsonObject) return null
        val name = element["name"]?.jsonPrimitive?.content ?: return null
        val contextArray = element["context"] as? JsonArray
        val context: Map<String, Any>? = contextArray?.mapNotNull { item ->
            (item as? JsonObject)?.let { ctxItem ->
                val key = ctxItem["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val value = ctxItem["value"]?.let { parseAnyValue(it) } ?: return@mapNotNull null
                key to value
            }
        }?.toMap()
        return Action(event = Event(name = name, context = context))
    }

    private fun parseOnTapAction(element: JsonElement): Action? {
        return when (element) {
            is JsonPrimitive -> Action(event = Event(name = element.content))
            is JsonObject -> {
                // Handle {"intent":"confirm"} format
                val intent = element["intent"]?.jsonPrimitive?.content
                if (intent != null) {
                    Action(event = Event(name = intent))
                } else {
                    // Handle other formats
                    val eventName = element["name"]?.jsonPrimitive?.content ?: return null
                    val context = element["context"]?.jsonObject?.mapValues { parseAnyValue(it.value) }?.filterValues { it != null } as? Map<String, Any>
                    Action(event = Event(name = eventName, context = context))
                }
            }
            else -> null
        }
    }

    private fun parseAction(element: JsonElement): Action? {
        return when (element) {
            is JsonPrimitive -> Action(event = Event(name = element.content))
            is JsonObject -> {
                val eventName = element["name"]?.jsonPrimitive?.content ?: return null
                val context = element["context"]?.jsonObject?.mapValues { parseAnyValue(it.value) }?.filterValues { it != null } as? Map<String, Any>
                Action(event = Event(name = eventName, context = context))
            }
            else -> null
        }
    }

    private fun parseAnyValue(element: JsonElement): Any? {
        return when (element) {
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
            is JsonObject -> element.mapValues { parseAnyValue(it.value) }.filterValues { it != null }
            is JsonArray -> element.map { parseAnyValue(it) }.filterNotNull()
            else -> null
        }
    }

    private fun parseAnyDynamicValue(element: JsonElement): DynamicValue<Any>? {
        return when (element) {
            is JsonPrimitive -> DynamicValue.LiteralValue(parseAnyValue(element) ?: return null)
            is JsonObject -> when {
                "literalString" in element -> DynamicValue.LiteralValue(element["literalString"]!!.jsonPrimitive.content)
                "path" in element -> DynamicValue.PathValue(element["path"]!!.jsonPrimitive.content)
                else -> DynamicValue.LiteralValue(parseAnyValue(element) ?: return null)
            }
            else -> null
        }
    }

    private fun parseOptions(element: JsonElement): List<Option>? {
        return when (element) {
            is JsonArray -> element.mapNotNull { opt ->
                (opt as? JsonObject)?.let { obj ->
                    val label = obj["label"]?.jsonPrimitive?.content ?: return@let null
                    val value = parseAnyValue(obj["value"]!!) ?: return@let null
                    Option(label = label, value = value)
                }
            }
            else -> null
        }
    }

    private fun parseDynamicValue(element: JsonElement): DynamicValue<String> {
        return when (element) {
            is JsonPrimitive -> DynamicValue.LiteralValue(element.content)
            is JsonObject -> when {
                "literalString" in element -> DynamicValue.LiteralValue(element["literalString"]!!.jsonPrimitive.content)
                "path" in element -> DynamicValue.PathValue(element["path"]!!.jsonPrimitive.content)
                else -> DynamicValue.LiteralValue(element.toString())
            }
            else -> DynamicValue.LiteralValue(element.toString())
        }
    }

    private fun parseChildren(element: JsonElement): ChildList? {
        return when (element) {
            is JsonArray -> ChildList.ArrayChildList(element.map { it.jsonPrimitive.content })
            is JsonObject -> when {
                "explicitList" in element -> {
                    val arr = element["explicitList"] as? JsonArray ?: return null
                    ChildList.ArrayChildList(arr.map { it.jsonPrimitive.content })
                }
                else -> null
            }
            else -> null
        }
    }

    private fun handleError(error: A2UIError, surfaceId: String) {
        // ✅ FIFO 淘汰
        while (_errors.size >= MAX_ERROR_COUNT) {
            _errors.removeFirst()
        }
        _errors.add(error)
        surfaceStates[surfaceId] = A2UIRendererState.Error(
            message = getErrorMessage(error),
            error = error
        )
        errorHandler?.handleError(error)
    }

    fun clearErrors() {
        _errors.clear()
        errorHandler?.clearErrors()
    }

    fun dismissError(index: Int) {
        if (index in _errors.indices) {
            _errors.removeAt(index)
        }
    }

    private fun getSurfaceId(message: String): String {
        return try {
            val jsonObj = json.parseToJsonElement(message)
            val surfaceId = jsonObj.jsonObject["createSurface"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj.jsonObject["updateComponents"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj.jsonObject["updateDataModel"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
                ?: jsonObj.jsonObject["deleteSurface"]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.content
            surfaceId ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun handleCreateSurface(createSurface: CreateSurface) {
        logger.log(A2UILogLevel.INFO, "Creating surface: ${createSurface.surfaceId}")
        if (surfaces.size >= MAX_SURFACES) {
            logger.log(A2UILogLevel.WARN, "Surface limit reached: ${surfaces.size}")
            throw IllegalStateException("Maximum surface count ($MAX_SURFACES) exceeded")
        }
        val surfaceId = createSurface.surfaceId
        // ✅ 校验 surfaceId 格式
        if (!A2UIProtocol.isValidId(surfaceId)) {
            logger.log(A2UILogLevel.WARN, "Invalid surfaceId format: $surfaceId")
            throw IllegalArgumentException("Invalid surfaceId format: $surfaceId")
        }
        // ✅ 重复创建 warn
        if (surfaces.containsKey(surfaceId)) {
            logger.log(A2UILogLevel.WARN, "Surface already exists, overwriting: $surfaceId")
        }
        dataModelProcessor.createSurface(surfaceId)
        surfaces[surfaceId] = SurfaceContext(
            surfaceId = surfaceId,
            catalogId = createSurface.catalogId,
            theme = createSurface.theme,
            sendDataModel = createSurface.sendDataModel
        )
        surfaceComponents[surfaceId] = mutableStateMapOf()
        surfaceStates[surfaceId] = A2UIRendererState.Idle
        surfaceChanges[surfaceId] = MutableStateFlow(Unit)
    }

    private fun handleUpdateComponents(updateComponents: UpdateComponents) {
        val surfaceId = updateComponents.surfaceId
        val components = updateComponents.components
        val componentMap = surfaceComponents[surfaceId] ?: run {
            logger.log(A2UILogLevel.WARN, "Surface not found: $surfaceId")
            return
        }

        val currentCount = componentMap.size
        if (currentCount + components.size > MAX_COMPONENTS_PER_SURFACE) {
            logger.log(A2UILogLevel.WARN, "Component limit exceeded for surface: $surfaceId")
            throw IllegalStateException("Maximum component count ($MAX_COMPONENTS_PER_SURFACE) exceeded")
        }

        components.forEach { component ->
            // ✅ 校验 component.id 格式
            if (!A2UIProtocol.isValidId(component.id)) {
                logger.log(A2UILogLevel.WARN, "Invalid component id format: ${component.id}, skipping")
                return@forEach
            }
            componentMap[component.id] = component
        }

        logger.log(A2UILogLevel.DEBUG, "Updated ${components.size} components in surface: $surfaceId")
    }

    private fun handleUpdateDataModel(updateDataModel: UpdateDataModel) {
        logger.log(A2UILogLevel.DEBUG, "Updating data model: ${updateDataModel.surfaceId} at path ${updateDataModel.path}")
        dataModelProcessor.updateDataModel(
            updateDataModel.surfaceId,
            updateDataModel.path,
            updateDataModel.value
        )
    }

    private fun handleDeleteSurface(deleteSurface: DeleteSurface) {
        logger.log(A2UILogLevel.INFO, "Deleting surface: ${deleteSurface.surfaceId}")
        val surfaceId = deleteSurface.surfaceId
        dataModelProcessor.deleteSurface(surfaceId)
        surfaces.remove(surfaceId)
        surfaceComponents.remove(surfaceId)
        surfaceStates.remove(surfaceId)
        surfaceChanges.remove(surfaceId)
        missingComponentWarnings.removeAll { it.startsWith("$surfaceId|") }
    }

    fun updateDataModel(surfaceId: String, path: String, value: Any?) {
        dataModelProcessor.updateDataModel(surfaceId, path, value)
        surfaceChanges[surfaceId]?.value = Unit
    }

    fun resolveValue(surfaceId: String, value: DynamicValue<*>?): Any? {
        return dataModelProcessor.resolveDynamicValue(surfaceId, value)
    }

    /**
     * 带作用域的值解析，用于 template 渲染
     */
    fun resolveValueWithScope(surfaceId: String, value: DynamicValue<*>?, scopePath: String?): Any? {
        return dataModelProcessor.resolveDynamicValueWithScope(surfaceId, value, scopePath)
    }

    fun getComponent(surfaceId: String, componentId: String): Component? {
        return surfaceComponents[surfaceId]?.get(componentId)
    }

    fun resolveComponentForRender(
        surfaceId: String,
        componentId: String,
        parentComponentId: String? = null,
    ): Component? {
        getComponent(surfaceId, componentId)?.let { return it }

        val bindingPaths = findBindingPathsForComponentId(surfaceId, componentId)
        val bindingPath = bindingPaths
            .sortedWith(compareBy<String>({ path -> path.count { it == '/' } }, { it.length }))
            .firstOrNull()

        return if (bindingPath != null) {
            logMissingComponentReference(
                surfaceId = surfaceId,
                parentComponentId = parentComponentId,
                componentId = componentId,
                bindingPath = bindingPath,
                hadMultipleMatches = bindingPaths.size > 1,
            )
            Component(
                id = "__fallback__${surfaceId}_$componentId",
                component = "Text",
                text = DynamicValue.PathValue<String>(bindingPath),
                variant = "body",
            )
        } else {
            logMissingComponentReference(
                surfaceId = surfaceId,
                parentComponentId = parentComponentId,
                componentId = componentId,
                bindingPath = null,
                hadMultipleMatches = false,
            )
            null
        }
    }

    fun getSurfaceContext(surfaceId: String): SurfaceContext? {
        return surfaces[surfaceId]
    }

    fun getSurfaceContextFlow(surfaceId: String): Flow<SurfaceContext?> {
        val changeFlow = surfaceChanges[surfaceId] ?: MutableStateFlow(Unit)
        
        return changeFlow.map {
            surfaces[surfaceId]
        }
    }

    fun getComponentFlow(surfaceId: String, componentId: String): Flow<Component?> {
        val changeFlow = surfaceChanges[surfaceId] ?: MutableStateFlow(Unit)
        
        return changeFlow.map {
            surfaceComponents[surfaceId]?.get(componentId)
        }
    }

    fun handleAction(surfaceId: String, action: Action) {
        logger.log(A2UILogLevel.INFO, "Handling action on surface: $surfaceId")
        when {
            action.event != null -> {
                logger.log(A2UILogLevel.DEBUG, "Action event: ${action.event.name}")
                _actionHandler.value?.onAction(surfaceId, action.event.name, action.event.context ?: emptyMap())
            }
            action.functionCall != null -> {
                logger.log(A2UILogLevel.DEBUG, "Action function: ${action.functionCall.call}")
                handleLocalFunction(action.functionCall)
            }
        }
    }

    private fun handleLocalFunction(functionCall: FunctionCall) {
        when (functionCall.call) {
            "openUrl" -> {
                val url = functionCall.args["url"] as? String
                if (url != null && ALLOWED_URL_SCHEMES.any { url.startsWith(it) }) {
                    _actionHandler.value?.openUrl(url)
                } else {
                    logger.log(A2UILogLevel.WARN, "Blocked unsafe URL scheme: $url")
                }
            }
            "showToast" -> {
                val message = functionCall.args["message"] as? String
                if (message != null) {
                    _actionHandler.value?.showToast(message)
                }
            }
        }
    }

    @Composable
    fun renderSurface(surfaceId: String): @Composable () -> Unit {
        val context = surfaces[surfaceId]
        val rootComponent = surfaceComponents[surfaceId]?.get("root")

        return {
            if (context != null && rootComponent != null) {
                registry.render(rootComponent, context)
            } else {
                androidx.compose.material3.CircularProgressIndicator()
            }
        }
    }

    fun getAllSurfaceIds(): List<String> {
        return surfaces.keys.toList()
    }

    fun getAllComponentIds(surfaceId: String): List<String> {
        return surfaceComponents[surfaceId]?.keys?.toList() ?: emptyList()
    }

    fun getSurfaceState(surfaceId: String): A2UIRendererState? {
        return surfaceStates[surfaceId]
    }

    fun getDataModel(surfaceId: String): DataModelState? {
        return dataModelProcessor.getDataModel(surfaceId)
    }

    private fun findBindingPathsForComponentId(surfaceId: String, componentId: String): List<String> {
        val surfaceData = dataModelProcessor.getSurfaceData(surfaceId) ?: return emptyList()
        val matches = linkedSetOf<String>()
        collectBindingPaths(
            value = surfaceData,
            currentPath = "",
            targetKey = componentId,
            matches = matches,
        )
        return matches.toList()
    }

    private fun collectBindingPaths(
        value: Any?,
        currentPath: String,
        targetKey: String,
        matches: MutableSet<String>,
    ) {
        when (value) {
            is Map<*, *> -> {
                value.forEach { (rawKey, nestedValue) ->
                    val key = rawKey as? String ?: return@forEach
                    val nextPath = "$currentPath/$key"
                    if (key == targetKey) {
                        matches += nextPath
                    }
                    collectBindingPaths(
                        value = nestedValue,
                        currentPath = nextPath,
                        targetKey = targetKey,
                        matches = matches,
                    )
                }
            }

            is List<*> -> {
                value.forEachIndexed { index, item ->
                    collectBindingPaths(
                        value = item,
                        currentPath = "$currentPath/$index",
                        targetKey = targetKey,
                        matches = matches,
                    )
                }
            }
        }
    }

    private fun logMissingComponentReference(
        surfaceId: String,
        parentComponentId: String?,
        componentId: String,
        bindingPath: String?,
        hadMultipleMatches: Boolean,
    ) {
        val warningKey = listOf(surfaceId, parentComponentId ?: "_", componentId, bindingPath ?: "_").joinToString("|")
        if (!missingComponentWarnings.add(warningKey)) {
            return
        }

        val parentLabel = parentComponentId ?: "unknown_parent"
        val message = if (bindingPath != null) {
            buildString {
                append("Missing referenced component '$componentId' under '$parentLabel' on surface '$surfaceId'; ")
                append("using fallback Text bound to '$bindingPath'")
                if (hadMultipleMatches) {
                    append(" after selecting the shortest matching data path")
                }
            }
        } else {
            "Missing referenced component '$componentId' under '$parentLabel' on surface '$surfaceId'; no fallback binding path found"
        }

        logger.log(A2UILogLevel.WARN, message)
        errorHandler?.handleError(
            A2UIError.ComponentError(componentId, message),
            ErrorSeverity.WARNING,
        )
    }

    fun saveState(): SavedRendererState {
        val dataModels = mutableMapOf<String, Map<String, Any?>>()
        surfaces.keys.forEach { surfaceId ->
            dataModels[surfaceId] = dataModelProcessor.getDataModel(surfaceId)?.getDataSnapshot() ?: emptyMap()
        }

        val components = mutableMapOf<String, Map<String, Component>>()
        surfaceComponents.forEach { (surfaceId, componentMap) ->
            components[surfaceId] = componentMap.toMap()
        }

        return SavedRendererState(
            surfaces = surfaces.toMap(),
            dataModels = dataModels,
            components = components
        )
    }

    fun restoreState(savedState: SavedRendererState) {
        // ✅ 先备份当前状态，失败时回滚
        val backupSurfaces = surfaces.toMap()
        val backupComponents = surfaceComponents.mapValues { (_, v) -> v.toMap() }
        val backupStates = surfaceStates.toMap()

        try {
            dispose()

            savedState.surfaces.forEach { (surfaceId, context) ->
                surfaces[surfaceId] = context
                surfaceComponents[surfaceId] = mutableStateMapOf()
                surfaceStates[surfaceId] = A2UIRendererState.Idle
                surfaceChanges[surfaceId] = MutableStateFlow(Unit)
                dataModelProcessor.createSurface(surfaceId)
            }

            savedState.dataModels.forEach { (surfaceId, data) ->
                dataModelProcessor.updateDataModel(surfaceId, "/", data)
            }

            savedState.components.forEach { (surfaceId, componentMap) ->
                val componentStateMap = surfaceComponents[surfaceId] ?: return@forEach
                componentMap.forEach { (componentId, component) ->
                    componentStateMap[componentId] = component
                }
            }

            logger.log(A2UILogLevel.INFO, "Restored state for ${savedState.surfaces.size} surfaces")
        } catch (e: Exception) {
            // ✅ 回滚
            logger.log(A2UILogLevel.ERROR, "Failed to restore state, rolling back: ${e.message}")
            dispose()

            backupSurfaces.forEach { (surfaceId, context) ->
                surfaces[surfaceId] = context
                surfaceComponents[surfaceId] = mutableStateMapOf()
                surfaceStates[surfaceId] = backupStates[surfaceId] ?: A2UIRendererState.Idle
                surfaceChanges[surfaceId] = MutableStateFlow(Unit)
                dataModelProcessor.createSurface(surfaceId)
            }
            backupComponents.forEach { (surfaceId, componentMap) ->
                val stateMap = surfaceComponents[surfaceId] ?: return@forEach
                componentMap.forEach { (id, comp) -> stateMap[id] = comp }
            }

            throw e
        }
    }

    fun dispose() {
        surfaces.clear()
        surfaceComponents.clear()
        surfaceStates.clear()
        surfaceChanges.clear()
        missingComponentWarnings.clear()
        dataModelProcessor.clear()
    }
}

data class SurfaceContext(
    val surfaceId: String,
    val catalogId: String,
    val theme: Theme? = null,
    val sendDataModel: Boolean = false,
    val renderDepth: Int = 0,
    /** Collection scope path for template rendering (e.g., "/users/0") */
    val scopePath: String? = null,
    /** Root component ID for this surface (set by beginRendering) */
    var rootComponentId: String = "root",
    /** Creation timestamp for tracking newest surface */
    val createdAt: Long = System.currentTimeMillis()
)

interface ActionHandler {
    fun onAction(surfaceId: String, actionName: String, context: Map<String, Any>)
    fun openUrl(url: String)
    fun showToast(message: String)
}

enum class A2UILogLevel {
    DEBUG, INFO, WARN, ERROR
}

interface A2UILogger {
    fun log(level: A2UILogLevel, message: String)
}

class DefaultLogger : A2UILogger {
    override fun log(level: A2UILogLevel, message: String) {
        when (level) {
            A2UILogLevel.DEBUG -> println("[A2UI DEBUG] $message")
            A2UILogLevel.INFO -> println("[A2UI INFO] $message")
            A2UILogLevel.WARN -> println("[A2UI WARN] $message")
            A2UILogLevel.ERROR -> println("[A2UI ERROR] $message")
        }
    }
}

@Composable
fun rememberA2UIRenderer(
    logger: A2UILogger = DefaultLogger()
): A2UIRenderer {
    return rememberSaveable(saver = A2UIRenderer.Saver) {
        A2UIRenderer(logger)
    }
}

/**
 * Serializer for Any type — handles JSON primitives, objects, and arrays
 */
object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as JsonEncoder
        jsonEncoder.encodeJsonElement(anyToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as JsonDecoder
        return jsonElementToAny(jsonDecoder.decodeJsonElement())
    }

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) })
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun jsonElementToAny(element: JsonElement): Any = when (element) {
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.longOrNull != null -> element.long
            element.doubleOrNull != null -> element.double
            else -> element.content
        }
        is JsonObject -> element.mapValues { jsonElementToAny(it.value) }
        is JsonArray -> element.map { jsonElementToAny(it) }
        else -> element.toString()
    }
}
