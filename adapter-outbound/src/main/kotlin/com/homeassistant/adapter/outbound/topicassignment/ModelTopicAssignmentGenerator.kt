package com.homeassistant.adapter.outbound.topicassignment

import com.homeassistant.application.port.output.topic.*
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.llm.completion.CompletionClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Asks the model which candidate Topics a memory belongs to. Organization is a blocking internal
 * maintenance call, so the single completion runs to the end on the caller's thread.
 */
internal class ModelTopicAssignmentGenerator(private val client: CompletionClient) : TopicAssignmentGenerator {
    override fun isAvailable(): Boolean = client.isAvailable()

    override fun assign(request: TopicAssignmentRequest): TopicAssignmentDecision = runBlocking {
        val response = client.complete(SYSTEM_PROMPT, render(request), OUTPUT_SCHEMA)
        JsonSerializer.json.decodeFromString<AssignmentResponse>(response).toDecision()
    }

    private fun render(request: TopicAssignmentRequest): String = buildString {
        appendLine("Memory:")
        appendLine(request.memory.content)
        appendLine()
        appendLine("Candidate topics:")
        if (request.candidates.isEmpty()) appendLine("(none)")
        request.candidates.forEach { appendLine("- id=${it.topicId}: ${it.description}") }
    }

    @Serializable
    private data class AssignmentResponse(val joins: List<Join>, val creations: List<Creation>) {
        fun toDecision() = TopicAssignmentDecision(
            joins.map { TopicJoin(it.topicId, it.category.trim(), it.revisedDescription.trim()) },
            creations.map { NewTopic(it.description.trim(), it.category.trim()) },
        )
    }

    @Serializable
    private data class Join(val topicId: Int, val category: String, val revisedDescription: String)

    @Serializable
    private data class Creation(val description: String, val category: String)

    private companion object {
        val SYSTEM_PROMPT = """
            You organize household memories into search Topics for a private second-brain system.
            A Topic is a short description of a subject that questions can be routed to, and its memories are
            sorted under short category names inside the Topic. One memory may belong to several Topics when it is
            genuinely useful to find it under each of them, for example a boiler repair cost under both boiler
            maintenance and housing expenses.
            Choose every candidate Topic the memory belongs to. For each one, give the category for the memory and
            a revised one-sentence description that keeps the Topic's existing scope and also covers the memory.
            If no candidate fits, create a new Topic, usually one, with a one-sentence description and a category.
            Assign the memory to at least one Topic. Write in the language of the memory. Do not add facts,
            names or dates that are not in the memory or the existing descriptions.
        """.trimIndent()

        const val OUTPUT_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["joins","creations"],
              "properties":{
                "joins":{
                  "type":"array",
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["topicId","category","revisedDescription"],
                    "properties":{
                      "topicId":{"type":"integer"},
                      "category":{"type":"string"},
                      "revisedDescription":{"type":"string"}
                    }
                  }
                },
                "creations":{
                  "type":"array",
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["description","category"],
                    "properties":{
                      "description":{"type":"string"},
                      "category":{"type":"string"}
                    }
                  }
                }
              }
            }
        """
    }
}

object TopicAssignmentGeneratorFactory {
    fun create(client: CompletionClient): TopicAssignmentGenerator = ModelTopicAssignmentGenerator(client)
}
