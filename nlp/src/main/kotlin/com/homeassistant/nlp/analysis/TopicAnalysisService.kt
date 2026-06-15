package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.MemoryClassification
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.nlp.MessageRole

/** Runs LLM topic analysis for any source document and stores valid topic candidates. */
class TopicAnalysisService(
    private val repository: TopicAnalysisRepository,
    private val backend: LlmBackend,
) {
    suspend fun analyze(document: SourceDocument): TopicAnalysisResult {
        if (document.records.isEmpty()) return TopicAnalysisResult(emptyList())

        val response = backend.complete(
            system = TopicAnalysisPrompt.system(),
            messages = listOf(Message(MessageRole.USER, renderDocument(document))),
            outputSchema = TopicAnalysisOutputContract.schema,
        )
        val raw = when (response) {
            is LlmResponse.Text -> response.content.value
            is LlmResponse.ToolCall -> throw TopicAnalysisException("Topic analyzer returned a tool call")
        }
        val dto = TopicAnalysisOutputContract.decode(raw)
        val topics = dto.topics.map { topic ->
            val classifications = parseClassifications(topic.classifications)
            val domains = parseDomains(topic.domains)
            val evidence = parseEvidence(document, topic.evidenceRecordIds)
            val claims = parseClaims(document, topic.claims)

            if (topic.title.isBlank()) throw TopicAnalysisException("Topic title must not be blank")
            if (topic.summary.isBlank()) throw TopicAnalysisException("Topic summary must not be blank")
            if (classifications.isEmpty()) throw TopicAnalysisException("Topic must include at least one classification")
            if (domains.isEmpty()) throw TopicAnalysisException("Topic must include at least one domain")
            if (evidence.isEmpty()) throw TopicAnalysisException("Topic must include at least one evidence record")
            if (claims.isEmpty()) throw TopicAnalysisException("Topic must include at least one claim")

            repository.createTopic(
                document = document,
                title = TopicTitle(topic.title.trim()),
                summary = TopicSummary(topic.summary.trim()),
                classifications = classifications,
                domains = domains,
                evidence = evidence,
                claims = claims,
            )
        }
        return TopicAnalysisResult(topics)
    }

    private fun parseClassifications(classifications: List<TopicClassificationLlmResponse>): List<MemoryClassification> =
        classifications.map { parseClassification(it) }.distinct()

    private fun parseClassification(classification: TopicClassificationLlmResponse): MemoryClassification =
        try {
            MemoryClassification.parse(classification.memoryKind.name, classification.memorySubtype.name)
        } catch (error: IllegalArgumentException) {
            throw TopicAnalysisException(
                "Invalid memory classification: ${classification.memoryKind.name}/${classification.memorySubtype.name}",
            )
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
                classification = parseClassification(claim.classification),
                certainty = claim.certainty,
                evidence = evidence,
            )
        }

    private fun renderDocument(document: SourceDocument): String =
        document.records.joinToString("\n") { "${it.id.value} | ${it.content}" }
}
