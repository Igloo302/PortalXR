package org.a2ui.compose.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A2UIStreamingJsonlProcessorTest {

    @Test
    fun appendChunk_buffersUntilNewline_andFlushProcessesRemainingMessage() {
        val dispatched = mutableListOf<String>()
        val processor = A2UIStreamingJsonlProcessor(dispatchMessage = dispatched::add)

        val createSurface = A2UIProtocol.createSurfaceMessage(surfaceId = "main")
        val updateData = A2UIProtocol.updateDataModelMessage(
            surfaceId = "main",
            path = "/statusText",
            value = "ready"
        )

        processor.appendChunk(createSurface.substring(0, 20))
        processor.appendChunk(createSurface.substring(20) + "\n" + updateData.substring(0, 18))

        assertEquals(listOf(createSurface), dispatched)
        assertEquals(1, processor.completedLineCount)
        assertEquals(1, processor.dispatchedMessageCount)

        processor.appendChunk(updateData.substring(18))
        processor.flush()

        assertEquals(listOf(createSurface, updateData), dispatched)
        assertEquals(2, processor.completedLineCount)
        assertEquals(2, processor.dispatchedMessageCount)
    }

    @Test
    fun appendChunk_extractsBackToBackJsonObjectsWithoutNewlines() {
        val dispatched = mutableListOf<String>()
        val processor = A2UIStreamingJsonlProcessor(dispatchMessage = dispatched::add)

        val createSurface = A2UIProtocol.createSurfaceMessage(surfaceId = "main")
        val updateData = A2UIProtocol.updateDataModelMessage(
            surfaceId = "main",
            path = "/statusText",
            value = "ready"
        )

        processor.appendChunk(createSurface + updateData)

        assertEquals(listOf(createSurface, updateData), dispatched)
        assertEquals(2, processor.completedLineCount)
        assertEquals(2, processor.dispatchedMessageCount)
    }

    @Test
    fun appendChunk_extractsPrettyPrintedJsonObjectsAcrossChunks() {
        val dispatched = mutableListOf<String>()
        val processor = A2UIStreamingJsonlProcessor(dispatchMessage = dispatched::add)

        val prettyObject = """
            {
              "version": "v0.10",
              "createSurface": {
                "surfaceId": "main",
                "catalogId": "standard"
              }
            }
        """.trimIndent()

        processor.appendChunk(prettyObject.substring(0, 24))
        assertTrue(dispatched.isEmpty())

        processor.appendChunk(prettyObject.substring(24))

        assertEquals(listOf(prettyObject.trim()), dispatched)
        assertEquals(1, processor.completedLineCount)
        assertEquals(1, processor.dispatchedMessageCount)
    }

    @Test
    fun processLine_acceptsV010Messages_andPassesThroughLegacyJson() {
        val dispatched = mutableListOf<String>()
        val processor = A2UIStreamingJsonlProcessor(dispatchMessage = dispatched::add)

        val validV010 = A2UIProtocol.createSurfaceMessage(surfaceId = "main")
        val legacyJson = "{\"text\":\"plain json\"}"

        processor.processLine(validV010)
        processor.processLine(legacyJson)

        assertEquals(listOf(validV010, legacyJson), dispatched)
    }

    @Test
    fun processLine_skipsGarbage_andUnknownV010Messages() {
        val dispatched = mutableListOf<String>()
        val processor = A2UIStreamingJsonlProcessor(dispatchMessage = dispatched::add)

        processor.processLine("not-json")
        processor.processLine("{\"version\":\"v0.10\",\"unknown\":{}}")
        processor.processLine("{invalid")

        assertEquals(emptyList<String>(), dispatched)
        assertEquals(0, processor.dispatchedMessageCount)
    }
}
