package com.homeassistant.app.routes

import com.homeassistant.domain.kakao.KakaoAnalysisPreviewRepository
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.NewTopicCandidateClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisRepository
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.nlp.topicanalysis.SourceDocument
import com.homeassistant.nlp.topicanalysis.SourceRecord
import com.homeassistant.nlp.topicanalysis.TopicAnalysisService
import com.homeassistant.nlp.topicanalysis.TopicDraft

/** Coordinates Kakao import with source-agnostic topic analysis for API callers. */
interface KakaoImportAnalyzeUseCase {
    suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult

    suspend fun savePreview(previewId: String): KakaoImportSaveResult
}

/**
 * API-level result for previewing Kakao messages and analyzing them into topics.
 *
 * @property previewId Identifier used to save this analysis preview later.
 * @property importedMessageCount Number of parsed Kakao messages in the preview.
 * @property topics Topic candidates produced from the previewed source document.
 */
data class KakaoImportAnalyzeResult(
    val previewId: String,
    val importedMessageCount: Int,
    val topics: List<NewTopicCandidate>,
)

data class KakaoImportSaveResult(
    val topics: List<TopicCandidate>,
)

class KakaoAnalysisPreviewNotFoundException(previewId: String) :
    RuntimeException("Kakao analysis preview not found: $previewId")

class KakaoImportAnalyzeService(
    private val importService: KakaoImportService,
    private val topicAnalysisService: TopicAnalysisService,
    private val topicRepository: TopicAnalysisRepository,
    private val previewRepository: KakaoAnalysisPreviewRepository,
) : KakaoImportAnalyzeUseCase {
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
        val topics = topicAnalysisService.analyze(document).topics.map { topic ->
            topic.toNewTopicCandidate(document)
        }
        val preview = previewRepository.createPreview(sourceFileName, text, topics)
        return KakaoImportAnalyzeResult(
            previewId = preview.previewId,
            importedMessageCount = messages.size,
            topics = topics,
        )
    }

    override suspend fun savePreview(previewId: String): KakaoImportSaveResult {
        val preview = previewRepository.findPreview(previewId)
            ?: throw KakaoAnalysisPreviewNotFoundException(previewId)
        val parsed = KakaoMessageParser.parse(preview.sourceFileName, preview.text)
        val imported = importService.import(preview.sourceFileName, preview.text)
        val refToStoredId = parsed.mapIndexed { index, message ->
            index + 1 to imported.messages.first { it.fingerprint == message.fingerprint }.id
        }.toMap()

        return KakaoImportSaveResult(
            topics = preview.topics.map { topic ->
                topicRepository.createTopic(topic.remapEvidenceRefs(refToStoredId))
            },
        )
    }

    private fun TopicDraft.toNewTopicCandidate(document: SourceDocument): NewTopicCandidate =
        NewTopicCandidate(
            sourceType = document.sourceType,
            sourceName = document.sourceName,
            title = title,
            summary = summary,
            memoryTypes = memoryTypes,
            domains = domains,
            evidenceRefs = evidence.map { it.ref },
            claims = claims.map { claim ->
                NewTopicCandidateClaim(
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidence.map { it.ref },
                )
            },
        )

    private fun NewTopicCandidate.remapEvidenceRefs(refToStoredId: Map<Int, Int>): NewTopicCandidate =
        copy(
            sourceType = sourceType,
            sourceName = sourceName,
            evidenceRefs = evidenceRefs.map { refToStoredId.getValue(it) },
            claims = claims.map { claim ->
                claim.copy(
                    evidenceRefs = claim.evidenceRefs.map { refToStoredId.getValue(it) },
                )
            },
        )
}
