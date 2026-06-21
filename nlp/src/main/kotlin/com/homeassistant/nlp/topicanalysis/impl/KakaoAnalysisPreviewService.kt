package com.homeassistant.nlp.topicanalysis.impl

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.domain.kakao.KakaoAnalysisPreviewRepository
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.NewTopicCandidateClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisRepository
import com.homeassistant.domain.topicanalysis.TopicDraft
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase

class KakaoAnalysisPreviewService(
    backend: LlmBackend,
    private val importService: KakaoImportService,
    private val  topicRepository: TopicAnalysisRepository,
    private val  previewRepository: KakaoAnalysisPreviewRepository
): TopicAnalysisUseCase {
    private val llmTopicAnalyzer = LlmTopicAnalyzer(backend)

    override suspend fun analyze(
        request: TopicAnalysisRequest
    ): TopicAnalysisResult {
        val sourceName = request.sourceName
        val messages = KakaoMessageParser.parse(sourceName, request.text)
        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = sourceName,
            records = messages.mapIndexed { index, message ->
                val recordNumber = index + 1
                SourceRecord(
                    id = "r$recordNumber",
                    ref = recordNumber,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
        val topics = llmTopicAnalyzer.analyze(document).topics.map { topic ->
            topic.toNewTopicCandidate(document)
        }
        val preview = previewRepository.createPreview(sourceName, request.text, topics)
        return TopicAnalysisResult(
            previewId = preview.previewId,
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = messages.count(),
            topics = topics
        )
    }


    override suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult {
        val preview = previewRepository.findPreview(previewId)
            ?: throw Exception("PREVIEW_NOT_FOUND: $previewId")
        val parsed = KakaoMessageParser.parse(preview.sourceFileName, preview.text)
        val imported = importService.import(preview.sourceFileName, preview.text)
        val refToStoredId = parsed.mapIndexed { index, message ->
            index + 1 to imported.messages.first { it.fingerprint == message.fingerprint }.id
        }.toMap()

        return TopicAnalysisSaveResult(
            previewId = previewId,
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
