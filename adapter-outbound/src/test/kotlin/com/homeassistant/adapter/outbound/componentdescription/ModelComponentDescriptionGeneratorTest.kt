package com.homeassistant.adapter.outbound.componentdescription

import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.llm.completion.CompletionImage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelComponentDescriptionGeneratorTest {
    @Test
    fun `sends only the given episodes and returns the trimmed description`() = runBlocking<Unit> {
        val client = RecordingClient("""{"description":"  보일러 점검 기록  "}""")

        assertEquals("보일러 점검 기록", ModelComponentDescriptionGenerator(client).generate(listOf("new", "old")))
        assertEquals("- new\n- old", client.messages.single())
    }

    @Test
    fun `older episodes past the input limit are left out but the newest is always kept`() {
        val generator = ModelComponentDescriptionGenerator(RecordingClient(""), maxInputChars = 12)

        assertEquals("- abcd\n- efg", generator.renderEpisodes(listOf("abcd", "efg", "h")))
        assertEquals("- far too long", generator.renderEpisodes(listOf("far too long", "x")))
    }

    @Test
    fun `a blank description fails`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { ModelComponentDescriptionGenerator(RecordingClient("""{"description":" "}""")).generate(listOf("m")) }
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
