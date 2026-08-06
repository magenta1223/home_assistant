package com.homeassistant.adapter.outbound.codex

import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisException
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.adapter.shared.json.JsonSerializer.encodeToString
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs LLM topic analysis for any source document and returns valid proposed topics. */
internal class CodexTopicExtractor(
    private val client: CodexCompletionClient,
    private val chunkSize: Int = CHUNK_SIZE,
) : TopicExtractor {
    override suspend fun analyze(document: SourceDocument): List<TopicProposal> =
        analyzeValidTopics(document).map { topic ->
            TopicProposal(
                title = topic.title,
                summary = topic.summary,
                categories = topic.categories,
                memories = topic.memories,
                memoryTypes = topic.memoryTypes,
                evidenceIds = topic.evidence.map { it.id },
            )
        }

    private suspend fun analyzeValidTopics(document: SourceDocument): List<ValidatedTopic> {
        if (document.records.isEmpty()) return emptyList()
        if (document.records.size <= chunkSize) return analyzeChunk(document)

        val chunkTopics = coroutineScope {
            chunkDocument(document)
                .map { chunk -> async { analyzeChunk(chunk) } }
                .awaitAll()
                .flatten()
        }
        return mergeTopics(document, chunkTopics)
    }

    private suspend fun analyzeChunk(document: SourceDocument): List<ValidatedTopic> =
        requestTopics(
            document = document,
            system = TopicAnalysisPrompt.system(),
            userMessage = renderDocument(document),
        )

    private suspend fun mergeTopics(
        document: SourceDocument,
        chunkTopics: List<ValidatedTopic>,
    ): List<ValidatedTopic> =
        requestTopics(
            document = document,
            system = TopicAnalysisPrompt.mergeSystem(),
            userMessage = renderTopicProposals(document, chunkTopics),
        )

    private suspend fun requestTopics(
        document: SourceDocument,
        system: String,
        userMessage: String,
    ): List<ValidatedTopic> {
        val raw = client.complete(
            system = system,
            userMessage = userMessage,
            outputSchema = TopicAnalysisOutputContract.schema,
        )
        val dto = TopicAnalysisOutputContract.decode(raw)
        val topics = dto.topics.map { topic ->
            val memoryTypes = topic.memoryTypes.distinct()
            val categories = parseCategories(topic.categories)
            val evidence = parseEvidence(document, topic.evidenceRecordIds)
            val memories = parseMemories(document, topic.memories)

            if (topic.title.isBlank()) throw TopicAnalysisException("Topic title must not be blank")
            if (topic.summary.isBlank()) throw TopicAnalysisException("Topic summary must not be blank")
            if (memoryTypes.isEmpty()) throw TopicAnalysisException("Topic must include at least one memory type")
            if (categories.isEmpty()) throw TopicAnalysisException("Topic must include at least one category")
            if (evidence.isEmpty()) throw TopicAnalysisException("Topic must include at least one evidence record")
            if (memories.isEmpty()) throw TopicAnalysisException("Topic must include at least one memory")

            ValidatedTopic(
                title = topic.title.trim(),
                summary = topic.summary.trim(),
                memoryTypes = memoryTypes,
                categories = categories,
                evidence = evidence,
                memories = memories,
            )
        }
        return topics
    }

    private fun chunkDocument(document: SourceDocument): List<SourceDocument> =
        document.records.chunked(chunkSize).map { records ->
            document.copy(records = records)
        }

    private fun parseCategories(categories: List<String>): List<String> =
        categories.map { category ->
            val normalized = category.trim().lowercase().replace(Regex("\\s+"), "-")
            if (normalized.isBlank()) throw TopicAnalysisException("Category tag must not be blank")
            normalized
        }.distinct()

    private fun parseEvidence(document: SourceDocument, evidenceRecordIds: List<String>): List<SourceRecord> =
        evidenceRecordIds.map { recordId ->
            document.records.firstOrNull { it.promptId == recordId }
                ?: throw TopicAnalysisException("Unknown evidence record id: $recordId")
        }.distinctBy { it.id }

    private fun parseMemories(document: SourceDocument, memories: List<MemoryLlmResponse>): List<MemoryProposal> =
        memories.map { memory ->
            val evidence = parseEvidence(document, memory.evidenceRecordIds)
            if (memory.text.isBlank()) throw TopicAnalysisException("Memory text must not be blank")
            if (memory.subject.isBlank()) throw TopicAnalysisException("Memory subject must not be blank")
            if (evidence.isEmpty()) throw TopicAnalysisException("Memory must include at least one evidence record")
            MemoryProposal(
                content = memory.text.trim(),
                subject = memory.subject.trim(),
                memoryType = memory.memoryType,
                certainty = memory.certainty,
                evidenceIds = evidence.map { it.id },
            )
        }

    private fun renderDocument(document: SourceDocument): String =
        document.records.joinToString("\n") { "${it.promptId} | ${it.content}" }

    private fun renderTopicProposals(document: SourceDocument, topics: List<ValidatedTopic>): String {
        val payload = TopicAnalysisLlmResponse(
            topics = topics.map { topic ->
                TopicLlmResponse(
                    title = topic.title,
                    summary = topic.summary,
                    memoryTypes = topic.memoryTypes,
                    categories = topic.categories,
                    evidenceRecordIds = topic.evidence.map { it.promptId },
                    memories = topic.memories.map { memory ->
                        MemoryLlmResponse(
                            text = memory.content,
                            subject = memory.subject,
                            memoryType = memory.memoryType,
                            certainty = memory.certainty,
                            evidenceRecordIds = memory.evidenceIds.map { evidenceId ->
                                document.records.first { it.id == evidenceId }.promptId
                            },
                        )
                    },
                )
            },
        )
        return payload.encodeToString()
    }

    private companion object {
        const val CHUNK_SIZE = 200
    }
}

/**
 * Topic analysis payload after schema decoding and domain validation.
 *
 * @property title Trimmed topic title.
 * @property summary Trimmed topic summary.
 * @property memoryTypes Distinct memory categories assigned to the topic.
 * @property categories Normalized category tags attached to the topic.
 * @property evidence Source records that support the topic.
 * @property memories Validated memories grouped under the topic.
 */
private data class ValidatedTopic(
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val categories: List<String>,
    val evidence: List<SourceRecord>,
    val memories: List<MemoryProposal>,
)
