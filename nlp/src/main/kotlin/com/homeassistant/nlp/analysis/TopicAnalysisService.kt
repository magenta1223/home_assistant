package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.models.Message
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.MessageRole
import com.homeassistant.core.nlp.SystemPrompt
import kotlinx.serialization.Serializable
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
        )
        val raw = when (response) {
            is LlmResponse.Text -> response.content.value
            is LlmResponse.ToolCall -> throw TopicAnalysisException("Topic analyzer returned a tool call")
        }
        val dto = decode(raw)
        val topics = dto.topics.map { topic ->
            val memoryTypes = parseMemoryTypes(topic.memoryTypes)
            val domains = parseDomains(topic.domains)
            val evidence = parseEvidence(document, topic.evidenceRecordIds)

            if (topic.title.isBlank()) throw TopicAnalysisException("Topic title must not be blank")
            if (topic.summary.isBlank()) throw TopicAnalysisException("Topic summary must not be blank")
            if (memoryTypes.isEmpty()) throw TopicAnalysisException("Topic must include at least one MemoryType")
            if (domains.isEmpty()) throw TopicAnalysisException("Topic must include at least one domain")
            if (evidence.isEmpty()) throw TopicAnalysisException("Topic must include at least one evidence record")

            repository.createTopic(
                document = document,
                title = TopicTitle(topic.title.trim()),
                summary = TopicSummary(topic.summary.trim()),
                memoryTypes = memoryTypes,
                domains = domains,
                evidence = evidence,
            )
        }
        return TopicAnalysisResult(topics)
    }

    private fun parseMemoryTypes(memoryTypes: List<String>): List<MemoryType> =
        memoryTypes.map { memoryType ->
            try {
                MemoryType.valueOf(memoryType.uppercase())
            } catch (_: IllegalArgumentException) {
                throw TopicAnalysisException("Unknown MemoryType: $memoryType")
            }
        }.distinct()

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

    private fun decode(raw: String): TopicAnalysisLlmResponse =
        try {
            json.decodeFromString<TopicAnalysisLlmResponse>(raw)
        } catch (error: SerializationException) {
            throw TopicAnalysisException("Failed to parse topic analysis response: ${error.message}")
        }

    private fun renderDocument(document: SourceDocument): String =
        document.records.joinToString("\n") { "${it.id.value} | ${it.content}" }

    @Serializable
    private data class TopicAnalysisLlmResponse(val topics: List<TopicLlmResponse>)

    @Serializable
    private data class TopicLlmResponse(
        val title: String,
        val summary: String,
        val memoryTypes: List<String>,
        val domains: List<String>,
        val evidenceRecordIds: List<String>,
    )

    companion object {
        private const val TOPIC_SYSTEM_PROMPT = """
주어진 source document 전체를 내용 기반으로 주제 분석하세요.
시간 간격으로 나누지 마세요. 같은 주제가 A-B-A 순서로 중간에 끊겨도 하나의 topic으로 병합하세요.
각 topic은 title, summary, memoryTypes, domains, evidenceRecordIds를 포함해야 합니다.
memoryTypes는 FACT, EVENT, COMMITMENT, PREFERENCE, DECISION 중 1개 이상만 사용하세요.
domains는 자유 태그이며 1개 이상 포함하세요.
evidenceRecordIds는 사용자 메시지에 제공된 r1, r2 같은 ID 중 1개 이상만 사용하세요.
실제로 말하지 않은 사실을 확정하지 말고, 선호와 완료된 사건을 구분하세요.
JSON만 반환하세요: {"topics":[{"title":"...","summary":"...","memoryTypes":["FACT"],"domains":["home"],"evidenceRecordIds":["r1"]}]}
"""
    }
}
