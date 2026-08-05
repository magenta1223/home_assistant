package com.homeassistant.adapter.outbound.codex

import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.domain.topicanalysis.NewTopicClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisException
import com.homeassistant.domain.topicanalysis.TopicAnalysisResult
import com.homeassistant.domain.topicanalysis.TopicDraft
import com.homeassistant.domain.topicanalysis.normalizeDomainTag
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Runs LLM topic analysis for any source document and stores valid topic candidates. */
internal class CodexTopicExtractor(
    private val client: CodexCompletionClient,
    private val chunkSize: Int = CHUNK_SIZE,
) : TopicExtractor {
    override suspend fun analyze(document: SourceDocument): TopicAnalysisResult {
        val topics = analyzeValidTopics(document).map { topic ->
            TopicDraft(
                title = topic.title,
                summary = topic.summary,
                memoryTypes = topic.memoryTypes,
                domains = topic.domains,
                evidence = topic.evidence,
                claims = topic.claims,
            )
        }
        return TopicAnalysisResult(topics)
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
            userMessage = renderTopicCandidates(chunkTopics),
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
            val domains = parseDomains(topic.domains)
            val evidence = parseEvidence(document, topic.evidenceRecordIds)
            val claims = parseClaims(document, topic.claims)

            if (topic.title.isBlank()) throw TopicAnalysisException("Topic title must not be blank")
            if (topic.summary.isBlank()) throw TopicAnalysisException("Topic summary must not be blank")
            if (memoryTypes.isEmpty()) throw TopicAnalysisException("Topic must include at least one memory type")
            if (domains.isEmpty()) throw TopicAnalysisException("Topic must include at least one domain")
            if (evidence.isEmpty()) throw TopicAnalysisException("Topic must include at least one evidence record")
            if (claims.isEmpty()) throw TopicAnalysisException("Topic must include at least one claim")

            ValidatedTopic(
                title = topic.title.trim(),
                summary = topic.summary.trim(),
                memoryTypes = memoryTypes,
                domains = domains,
                evidence = evidence,
                claims = claims,
            )
        }
        return topics
    }

    private fun chunkDocument(document: SourceDocument): List<SourceDocument> =
        document.records.chunked(chunkSize).map { records ->
            document.copy(records = records)
        }

    private fun parseDomains(domains: List<String>): List<String> =
        domains.map { domain ->
            try {
                normalizeDomainTag(domain)
            } catch (_: IllegalArgumentException) {
                throw TopicAnalysisException("Domain tag must not be blank")
            }
        }.distinct()

    private fun parseEvidence(document: SourceDocument, evidenceRecordIds: List<String>): List<SourceRecord> =
        evidenceRecordIds.map { recordId ->
            document.records.firstOrNull { it.id == recordId }
                ?: throw TopicAnalysisException("Unknown evidence record id: $recordId")
        }.distinctBy { it.id }

    private fun parseClaims(document: SourceDocument, claims: List<TopicClaimLlmResponse>): List<NewTopicClaim> =
        claims.map { claim ->
            val evidence = parseEvidence(document, claim.evidenceRecordIds)
            if (claim.text.isBlank()) throw TopicAnalysisException("Claim text must not be blank")
            if (claim.subject.isBlank()) throw TopicAnalysisException("Claim subject must not be blank")
            if (evidence.isEmpty()) throw TopicAnalysisException("Claim must include at least one evidence record")
            NewTopicClaim(
                text = claim.text.trim(),
                subject = claim.subject.trim(),
                memoryType = claim.memoryType,
                certainty = claim.certainty,
                evidence = evidence,
            )
        }

    private fun renderDocument(document: SourceDocument): String =
        document.records.joinToString("\n") { "${it.id} | ${it.content}" }

    private fun renderTopicCandidates(topics: List<ValidatedTopic>): String {
        val payload = TopicAnalysisLlmResponse(
            topics = topics.map { topic ->
                TopicLlmResponse(
                    title = topic.title,
                    summary = topic.summary,
                    memoryTypes = topic.memoryTypes,
                    domains = topic.domains,
                    evidenceRecordIds = topic.evidence.map { it.id },
                    claims = topic.claims.map { claim ->
                        TopicClaimLlmResponse(
                            text = claim.text,
                            subject = claim.subject,
                            memoryType = claim.memoryType,
                            certainty = claim.certainty,
                            evidenceRecordIds = claim.evidence.map { it.id },
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
 * @property domains Normalized domain tags attached to the topic.
 * @property evidence Source records that support the topic.
 * @property claims Validated claims grouped under the topic.
 */
private data class ValidatedTopic(
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val domains: List<String>,
    val evidence: List<SourceRecord>,
    val claims: List<NewTopicClaim>,
)
