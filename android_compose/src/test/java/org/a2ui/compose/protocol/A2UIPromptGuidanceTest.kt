package org.a2ui.compose.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

class A2UIPromptGuidanceTest {

    @Test
    fun toolSummaryInstruction_coversCommonDynamicSceneCards() {
        val prompt = A2UIPromptGuidance.toolSummaryInstruction()

        listOf("天气", "路线", "地点", "票务", "媒体", "车辆", "诊断", "表单", "任务").forEach { keyword ->
            assertTrue("missing keyword: $keyword", prompt.contains(keyword))
        }
        assertTrue(prompt.contains("A2UI JSONL"))
        assertTrue(prompt.contains("自然语言"))
    }

    @Test
    fun middlewareInstruction_requiresAllReferencedComponentIdsToBeDefined() {
        val prompt = A2UIPromptGuidance.middlewareInstruction()

        assertTrue(prompt.contains("Any id referenced by child / children.array / componentId must be defined"))
    }

    @Test
    fun promptGuidance_forbidsMismatchedToolDomainsAndFactFabrication() {
        val middlewarePrompt = A2UIPromptGuidance.middlewareInstruction()
        val summaryPrompt = A2UIPromptGuidance.toolSummaryInstruction()

        assertTrue(middlewarePrompt.contains("Tool domain must match user intent"))
        assertTrue(summaryPrompt.contains("do not fabricate UI or facts"))
    }
}
