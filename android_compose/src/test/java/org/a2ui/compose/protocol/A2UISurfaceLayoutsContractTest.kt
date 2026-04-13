package org.a2ui.compose.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class A2UISurfaceLayoutsContractTest {

    @Test
    fun chatSurfaceComponents_bootstrapRootMessagesAndStatusContainers() {
        val components = A2UISurfaceLayouts.chatSurfaceComponents()
        val json = parseArray(A2UIProtocol.componentsJson(components))

        assertRoot(json)
        assertChildren(json, "content", listOf("messages_container", "status_text"))
        assertChildren(json, "messages_container", emptyList())

        val status = findComponent(json, "status_text")
        assertEquals("Text", status.getValue("component").jsonPrimitive.content)
        assertEquals("/statusText", status.getValue("text").jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun streamingSurfaceComponents_useSharedRootContainerPattern() {
        val components = A2UISurfaceLayouts.streamingSurfaceComponents("/streamingText")
        val json = parseArray(A2UIProtocol.componentsJson(components))

        assertRoot(json)
        assertChildren(json, "content", listOf("streaming_text"))

        val text = findComponent(json, "streaming_text")
        assertEquals("Text", text.getValue("component").jsonPrimitive.content)
        assertEquals("/streamingText", text.getValue("text").jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun responseCardComponents_useSharedRootContainerPattern() {
        val components = A2UISurfaceLayouts.responseCardComponents("/response/text")
        val json = parseArray(A2UIProtocol.componentsJson(components))

        assertRoot(json)
        assertChildren(json, "content", listOf("response_text", "response_dismiss"))

        val text = findComponent(json, "response_text")
        assertEquals("Text", text.getValue("component").jsonPrimitive.content)
        assertEquals("/response/text", text.getValue("text").jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun messagesContainerComponent_wrapsChildrenAsArray() {
        val component = A2UISurfaceLayouts.messagesContainerComponent(listOf("msg_1", "msg_2"))
        val json = parseArray(A2UIProtocol.componentsJson(listOf(component)))

        assertChildren(json, "messages_container", listOf("msg_1", "msg_2"))
    }

    private fun assertRoot(components: JsonArray) {
        val root = components[0].jsonObject
        assertEquals("root", root.getValue("id").jsonPrimitive.content)
        assertEquals("Card", root.getValue("component").jsonPrimitive.content)
        assertEquals("content", root.getValue("child").jsonPrimitive.content)
    }

    private fun assertChildren(components: JsonArray, componentId: String, expected: List<String>) {
        val component = findComponent(components, componentId)
        val array = component.getValue("children").jsonObject.getValue("array").jsonArray
        assertEquals(expected.size, array.size)
        expected.forEachIndexed { index, value ->
            assertEquals(value, array[index].jsonPrimitive.content)
        }
    }

    private fun findComponent(components: JsonArray, componentId: String): JsonObject {
        return components.firstOrNull { element ->
            element.jsonObject.getValue("id").jsonPrimitive.content == componentId
        }?.jsonObject ?: error("Missing component: $componentId")
    }

    private fun parseArray(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray
}
