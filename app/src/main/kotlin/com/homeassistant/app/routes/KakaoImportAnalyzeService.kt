package com.homeassistant.app.routes

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.NewTopicCandidateClaim
import com.homeassistant.domain.topicanalysis.NewTopicCandidateEvidence
import com.homeassistant.domain.topicanalysis.TopicAnalysisRepository
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.nlp.topicanalysis.SourceDocument
import com.homeassistant.nlp.topicanalysis.SourceRecord
import com.homeassistant.nlp.topicanalysis.TopicAnalysisService
import com.homeassistant.nlp.topicanalysis.TopicDraft

/** Coordinates Kakao import with source-agnostic topic analysis for API callers. */
interface KakaoImportAnalyzeUseCase {
    suspend fun importAndAnalyze(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult

    suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult
}

/**
 * API-level result for importing Kakao messages and analyzing them into topics.
 *
 * @property importedMessageCount Number of newly imported or previewed Kakao messages.
 * @property topics Topic candidates produced from the imported source document.
 */
data class KakaoImportAnalyzeResult(
    val importedMessageCount: Int,
    val topics: List<TopicCandidate>,
)

class KakaoImportAnalyzeService(
    private val importService: KakaoImportService,
    private val topicAnalysisService: TopicAnalysisService,
    private val topicRepository: TopicAnalysisRepository,
) : KakaoImportAnalyzeUseCase {
    override suspend fun importAndAnalyze(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        val imported = importService.import(sourceFileName, text)
        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = sourceFileName,
            records = imported.messages.mapIndexed { index, message ->
                SourceRecord(
                    id = "r${index + 1}",
                    ref = message.id,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
        return KakaoImportAnalyzeResult(
            importedMessageCount = imported.importedMessageCount,
            topics = topicAnalysisService.analyze(document).topics.map { topic ->
                topicRepository.createTopic(topic.toNewTopicCandidate(document))
            },
        )
    }

    override suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        val messages = KakaoMessageParser.parse(sourceFileName, text)
        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = sourceFileName,
            records = messages.mapIndexed { index, message ->
                val recordNumber = index + 1
                SourceRecord(
                    id = "r$recordNumber",
                    ref = recordNumber,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
        return KakaoImportAnalyzeResult(
            importedMessageCount = messages.size,
            topics = topicAnalysisService.analyze(document).topics.mapIndexed { index, topic ->
                topic.toPreviewCandidate(document, index + 1)
            },
        )
    }

    private fun TopicDraft.toPreviewCandidate(document: SourceDocument, topicId: Int): TopicCandidate =
        TopicCandidate(
            id = topicId,
            sourceType = document.sourceType,
            sourceName = document.sourceName,
            title = title,
            summary = summary,
            memoryTypes = memoryTypes,
            domains = domains,
            evidenceRefs = evidence.map { it.ref },
            claims = claims.mapIndexed { claimIndex, claim ->
                TopicClaim(
                    id = claimIndex + 1,
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidence.map { it.ref },
                )
            },
            status = CandidateStatus.PENDING,
        )

    private fun TopicDraft.toNewTopicCandidate(document: SourceDocument): NewTopicCandidate =
        NewTopicCandidate(
            sourceType = document.sourceType,
            sourceName = document.sourceName,
            title = title,
            summary = summary,
            memoryTypes = memoryTypes,
            domains = domains,
            evidence = evidence.map { NewTopicCandidateEvidence(id = it.id, ref = it.ref) },
            claims = claims.map { claim ->
                NewTopicCandidateClaim(
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidence = claim.evidence.map { NewTopicCandidateEvidence(id = it.id, ref = it.ref) },
                )
            },
        )
}
