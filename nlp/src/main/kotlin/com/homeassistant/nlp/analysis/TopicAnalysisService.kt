package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.nlp.MessageRole
import com.homeassistant.core.nlp.SystemPrompt
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Runs LLM topic analysis for any source document and stores valid topic candidates. */
class TopicAnalysisService(
    private val repository: TopicAnalysisRepository,
    private val backend: LlmBackend,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(document: SourceDocument): TopicAnalysisResult {
        if (document.records.isEmpty()) return TopicAnalysisResult(emptyList())

        val response = backend.complete(
            system = SystemPrompt(TOPIC_SYSTEM_PROMPT),
            messages = listOf(Message(MessageRole.USER, renderDocument(document))),
            outputSchema = TopicAnalysisOutputSchema.value,
        )
        val raw = when (response) {
            is LlmResponse.Text -> response.content.value
            is LlmResponse.ToolCall -> throw TopicAnalysisException("Topic analyzer returned a tool call")
        }
        val dto = decode(raw)
        val topics = dto.topics.map { topic ->
            val memoryTypes = topic.memoryTypes.map { MemoryType.valueOf(it.name) }.distinct()
            val domains = parseDomains(topic.domains)
            val evidence = parseEvidence(document, topic.evidenceRecordIds)
            val claims = parseClaims(document, topic.claims)

            if (topic.title.isBlank()) throw TopicAnalysisException("Topic title must not be blank")
            if (topic.summary.isBlank()) throw TopicAnalysisException("Topic summary must not be blank")
            if (memoryTypes.isEmpty()) throw TopicAnalysisException("Topic must include at least one MemoryType")
            if (domains.isEmpty()) throw TopicAnalysisException("Topic must include at least one domain")
            if (evidence.isEmpty()) throw TopicAnalysisException("Topic must include at least one evidence record")
            if (claims.isEmpty()) throw TopicAnalysisException("Topic must include at least one claim")

            repository.createTopic(
                document = document,
                title = TopicTitle(topic.title.trim()),
                summary = TopicSummary(topic.summary.trim()),
                memoryTypes = memoryTypes,
                domains = domains,
                evidence = evidence,
                claims = claims,
            )
        }
        return TopicAnalysisResult(topics)
    }

    private fun parseDomains(domains: List<String>): List<DomainTag> =
        domains.map { domain ->
            try {
                normalizeDomainTag(domain)
            } catch (_: IllegalArgumentException) {
                throw TopicAnalysisException("Domain tag must not be blank")
            }
        }.distinct()

    private fun parseEvidence(document: SourceDocument, evidenceRecordIds: List<String>): List<SourceRecord> =
        evidenceRecordIds.map { recordId ->
            document.records.firstOrNull { it.id.value == recordId }
                ?: throw TopicAnalysisException("Unknown evidence record id: $recordId")
        }.distinctBy { it.id }

    private fun parseClaims(document: SourceDocument, claims: List<TopicClaimLlmResponse>): List<NewTopicClaim> =
        claims.map { claim ->
            val evidence = parseEvidence(document, claim.evidenceRecordIds)
            if (claim.text.isBlank()) throw TopicAnalysisException("Claim text must not be blank")
            if (claim.subject.isBlank()) throw TopicAnalysisException("Claim subject must not be blank")
            if (evidence.isEmpty()) throw TopicAnalysisException("Claim must include at least one evidence record")
            NewTopicClaim(
                text = ClaimText(claim.text.trim()),
                subject = ClaimSubject(claim.subject.trim()),
                memoryType = MemoryType.valueOf(claim.memoryType.name),
                certainty = claim.certainty,
                evidence = evidence,
            )
        }

    private fun decode(raw: String): TopicAnalysisLlmResponse =
        try {
            json.decodeFromString<TopicAnalysisLlmResponse>(raw)
        } catch (error: SerializationException) {
            throw TopicAnalysisException("Failed to parse topic analysis response: ${error.message}")
        }

    private fun renderDocument(document: SourceDocument): String =
        document.records.joinToString("\n") { "${it.id.value} | ${it.content}" }

    companion object {
        private const val TOPIC_SYSTEM_PROMPT = """
주어진 source document 전체를 내용 기반으로 주제 분석하세요.
시간 간격으로 나누지 마세요. 같은 주제가 A-B-A 순서로 중간에 끊겨도 하나의 topic으로 병합하세요.
각 topic은 가족/집 second brain에 승인 후보로 올릴 수 있는 evidence-backed claim을 1개 이상 포함해야 합니다.
evidenceRecordIds는 사용자 메시지에 제공된 r1, r2 같은 ID만 사용하세요.
실제로 말하지 않은 사실을 확정하지 말고, 관찰/발화/추론/불확실성을 구분하세요.
JSON만 반환하세요.
"""
    }
}
