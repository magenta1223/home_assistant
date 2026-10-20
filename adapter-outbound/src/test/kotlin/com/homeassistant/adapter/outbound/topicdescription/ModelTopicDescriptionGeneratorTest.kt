package com.homeassistant.adapter.outbound.topicdescription

import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.llm.completion.CompletionImage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelTopicDescriptionGeneratorTest {
    @Test
    fun `sends only the given memories and returns the trimmed description`() = runBlocking<Unit> {
        val client = RecordingClient("""{"description":"  보일러 점검 기록  "}""")

        assertEquals("보일러 점검 기록", ModelTopicDescriptionGenerator(client).generate(listOf("new", "old")))
        assertEquals("- new\n- old", client.messages.single())
    }

    @Test
    fun `older memories past the input limit are left out but the newest is always kept`() {
        val generator = ModelTopicDescriptionGenerator(RecordingClient(""), maxInputChars = 12)

        assertEquals("- abcd\n- efg", generator.renderMemories(listOf("abcd", "efg", "h")))
        assertEquals("- far too long", generator.renderMemories(listOf("far too long", "x")))
    }

    @Test
    fun `a blank description fails`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { ModelTopicDescriptionGenerator(RecordingClient("""{"description":" "}""")).generate(listOf("m")) }
        }
    }

    private class RecordingClient(private val response: String) : CompletionClient {
        val messages = mutableListOf<String>()

        override suspend fun complete(system: String, userMessage: String, outputSchema: String, images: List<CompletionImage>): String {
            messages += userMessage
            return response
        }
    }
}
