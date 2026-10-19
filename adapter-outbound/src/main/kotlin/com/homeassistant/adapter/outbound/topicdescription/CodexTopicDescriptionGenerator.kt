package com.homeassistant.adapter.outbound.topicdescription

import com.homeassistant.application.port.output.topic.TopicDescriptionGenerator
import com.homeassistant.codex.completion.CodexCompletionClientFactory
import com.homeassistant.codex.completion.CompletionClient
import com.homeassistant.common.json.JsonSerializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * xMemory theme summary: the model names what the organized memories share. Organization is a blocking
 * internal maintenance call, so the single completion runs to the end on the caller's thread.
 */
internal class CodexTopicDescriptionGenerator(private val client: CompletionClient) : TopicDescriptionGenerator {
    override fun describe(memoryContents: List<String>): String = runBlocking {
        val response = client.complete(SYSTEM_PROMPT, render(memoryContents), OUTPUT_SCHEMA)
        JsonSerializer.json.decodeFromString<TopicDescriptionResponse>(response).description.trim()
    }

    private fun render(memoryContents: List<String>): String =
        memoryContents.mapIndexed { index, content -> "${index + 1}. $content" }.joinToString("\n")

    @Serializable
    private data class TopicDescriptionResponse(val description: String)

    private companion object {
        val SYSTEM_PROMPT = """
            You summarize a group of household memories into one Topic description for a private second-brain system.
            Describe in one short sentence what subject the memories have in common, so the description can be used
            to decide whether a question should search this Topic. Write in the language the memories are written in.
            Do not add facts, names, dates or conclusions that are not in the memories, and do not list every memory.
        """.trimIndent()

        const val OUTPUT_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["description"],
              "properties":{"description":{"type":"string"}}
            }
        """
    }
}

object TopicDescriptionGeneratorFactory {
    fun create(): TopicDescriptionGenerator = CodexTopicDescriptionGenerator(CodexCompletionClientFactory.create())
}
