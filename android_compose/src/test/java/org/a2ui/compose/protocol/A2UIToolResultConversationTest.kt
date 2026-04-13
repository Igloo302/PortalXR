package org.a2ui.compose.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2UIToolResultConversationTest {

    @Test
    fun buildFollowUpMessages_addsAssistantToolCallsBeforeSummaryAndToolResults() {
        val originalMessages = listOf(
            A2UIConversationMessage(role = "system", content = "base system"),
            A2UIConversationMessage(role = "user", content = "帮我看下北京天气")
        )
        val toolCalls = listOf(
            A2UIToolCall(
                id = "call_weather",
                name = "get_current_weather",
                argumentsJson = """{"lat":"39.9","lon":"116.4","days":"1"}"""
            )
        )
        val toolResults = linkedMapOf(
            "call_weather" to """{"current_weather":{"temperature":25.0,"wind_speed":10.0}}"""
        )

        val followUpMessages = A2UIToolResultConversation.buildFollowUpMessages(
            originalMessages = originalMessages,
            toolCalls = toolCalls,
            toolResults = toolResults
        )

        assertEquals(5, followUpMessages.size)
        assertEquals("assistant", followUpMessages[2].role)
        assertNull(followUpMessages[2].content)
        assertEquals("get_current_weather", followUpMessages[2].toolCalls?.single()?.name)

        assertEquals("system", followUpMessages[3].role)
        assertTrue(followUpMessages[3].content.orEmpty().contains("A2UI"))

        assertEquals("tool", followUpMessages[4].role)
        assertEquals("call_weather", followUpMessages[4].toolCallId)
        assertEquals("get_current_weather", followUpMessages[4].name)
    }

    @Test
    fun buildFollowUpMessages_keepsDeclaredToolOrderAcrossDifferentDynamicScenes() {
        val toolCalls = listOf(
            A2UIToolCall(id = "weather", name = "get_current_weather", argumentsJson = "{}"),
            A2UIToolCall(id = "route", name = "get_route", argumentsJson = "{}"),
            A2UIToolCall(id = "vehicle", name = "get_vehicle_status", argumentsJson = "{}")
        )
        val toolResults = linkedMapOf(
            "vehicle" to """{"battery":78}""",
            "weather" to """{"summary":"晴"}""",
            "route" to """{"eta_minutes":18}"""
        )

        val followUpMessages = A2UIToolResultConversation.buildFollowUpMessages(
            originalMessages = emptyList(),
            toolCalls = toolCalls,
            toolResults = toolResults
        )

        val toolTurns = followUpMessages.filter { it.role == "tool" }
        assertEquals(listOf("weather", "route", "vehicle"), toolTurns.map { it.toolCallId })
        assertEquals(
            listOf("get_current_weather", "get_route", "get_vehicle_status"),
            toolTurns.map { it.name }
        )
    }
}
