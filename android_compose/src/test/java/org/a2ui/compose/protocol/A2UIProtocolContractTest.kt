package org.a2ui.compose.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2UIProtocolContractTest {

    @Test
    fun createSurfaceMessage_usesV010ContractAndOptionalTheme() {
        val json = A2UIProtocol.createSurfaceMessage(
            surfaceId = "main",
            catalogId = "standard",
            primaryColor = "#1976D2"
        )

        val root = parseObject(json)
        assertEquals("v0.10", root.getValue("version").jsonPrimitive.content)

        val createSurface = root.getValue("createSurface").jsonObject
        assertEquals("main", createSurface.getValue("surfaceId").jsonPrimitive.content)
        assertEquals("standard", createSurface.getValue("catalogId").jsonPrimitive.content)
        assertEquals("#1976D2", createSurface.getValue("theme").jsonObject.getValue("primaryColor").jsonPrimitive.content)
    }

    @Test
    fun updateComponentsMessage_serializesDemoCompatibleComponents() {
        val components = listOf(
            mapOf(
                "id" to "root",
                "component" to "Card",
                "child" to "content"
            ),
            mapOf(
                "id" to "content",
                "component" to "Column",
                "children" to mapOf("array" to listOf("title", "desc"))
            ),
            mapOf(
                "id" to "title",
                "component" to "Text",
                "text" to mapOf("literal" to "Hello")
            )
        )

        val json = A2UIProtocol.updateComponentsMessage(
            surfaceId = "main",
            components = components
        )

        val root = parseObject(json)
        assertEquals("v0.10", root.getValue("version").jsonPrimitive.content)

        val updateComponents = root.getValue("updateComponents").jsonObject
        assertEquals("main", updateComponents.getValue("surfaceId").jsonPrimitive.content)

        val serializedComponents = updateComponents.getValue("components").jsonArray
        assertEquals(3, serializedComponents.size)

        val rootComponent = serializedComponents[0].jsonObject
        assertEquals("root", rootComponent.getValue("id").jsonPrimitive.content)
        assertEquals("Card", rootComponent.getValue("component").jsonPrimitive.content)
        assertFalse(rootComponent.containsKey("type"))

        val contentComponent = serializedComponents[1].jsonObject
        val children = contentComponent.getValue("children").jsonObject
        assertEquals(2, children.getValue("array").jsonArray.size)
        assertEquals("title", children.getValue("array").jsonArray[0].jsonPrimitive.content)
        assertEquals("desc", children.getValue("array").jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun updateDataModelMessage_serializesNullAndPathUpdates() {
        val json = A2UIProtocol.updateDataModelMessage(
            surfaceId = "main",
            path = "/statusText",
            value = null
        )

        val root = parseObject(json)
        assertEquals("v0.10", root.getValue("version").jsonPrimitive.content)

        val updateDataModel = root.getValue("updateDataModel").jsonObject
        assertEquals("main", updateDataModel.getValue("surfaceId").jsonPrimitive.content)
        assertEquals("/statusText", updateDataModel.getValue("path").jsonPrimitive.content)
        assertTrue(updateDataModel.getValue("value").toString() == "null")
    }

    @Test
    fun deleteSurfaceMessage_serializesDeleteSurfaceEnvelope() {
        val json = A2UIProtocol.deleteSurfaceMessage(surfaceId = "main")

        val root = parseObject(json)
        assertEquals("v0.10", root.getValue("version").jsonPrimitive.content)
        assertEquals("main", root.getValue("deleteSurface").jsonObject.getValue("surfaceId").jsonPrimitive.content)
    }

    @Test
    fun arrayChildren_wrapsIdsInDemoCompatibleShape() {
        val children = A2UIProtocol.arrayChildren(listOf("alpha", "beta"))
        val array = children.get("array").asJsonArray

        assertEquals(2, array.size())
        assertEquals("alpha", array[0].asString)
        assertEquals("beta", array[1].asString)
    }


    @Test
    fun containsA2UIMessages_detectsPrettyPrintedObjectsWithoutJsonlBoundaries() {
        val payload = """
            before text
            {
              "version": "v0.10",
              "createSurface": {
                "surfaceId": "main",
                "catalogId": "standard"
              }
            }
            after text
        """.trimIndent()

        assertTrue(A2UIProtocol.containsA2UIMessages(payload))
        assertFalse(A2UIProtocol.containsA2UIMessages("plain response only"))
    }

    private fun parseObject(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
