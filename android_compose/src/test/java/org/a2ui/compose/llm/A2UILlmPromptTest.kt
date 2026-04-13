package org.a2ui.compose.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class A2UILlmPromptTest {

    @Test
    fun systemPrompt_requiresJsonlRootAndStreamingSequence() {
        val prompt = A2UILlmPrompt.systemPrompt()

        assertTrue(prompt.contains("A2UI v0.10"))
        assertTrue(prompt.contains("JSONL"))
        assertTrue(prompt.contains("surfaceId"))
        assertTrue(prompt.contains("root"))
        assertTrue(prompt.contains("children"))
        assertTrue(prompt.contains("\"array\""))
        assertTrue(prompt.contains("createSurface"))
        assertTrue(prompt.contains("updateComponents"))
        assertTrue(prompt.contains("updateDataModel"))
        assertTrue(prompt.contains("只输出JSONL内容"))
    }
}
