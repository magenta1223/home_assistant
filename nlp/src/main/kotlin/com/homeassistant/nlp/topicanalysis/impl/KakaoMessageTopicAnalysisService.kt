package com.homeassistant.nlp.topicanalysis.impl

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanalysis.TopicDraft
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndex
import com.homeassistant.domain.topicanswer.UnavailableTopicClaimSearchIndex
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSelectionSaveRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase

class KakaoMessageTopicAnalysisService(
    backend: LlmBackend,
    private val importService: KakaoImportService,
    private val topicRepository: TopicAnalysisStore,
    private val previewRepository: TopicAnalysisPreviewStore,
    private val topicClaimSearchIndex: TopicClaimSearchIndex = UnavailableTopicClaimSearchIndex,
): TopicAnalysisUseCase() {
    private val topicAnalyzer = LlmTopicAnalyzer(backend)

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
        val topics = topicAnalyzer.analyze(document).topics.map { topic ->
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

        return savePreviewTopics(
            previewId = previewId,
            sourceFileName = preview.sourceFileName,
            text = preview.text,
            topics = preview.topics,
        )
    }

    override suspend fun saveSelectedAnalysis(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        val preview = previewRepository.findPreview(request.previewId)
            ?: throw Exception("PREVIEW_NOT_FOUND: ${request.previewId}")
        val selectedTopics = request.selectedTopicIndices
            .sorted()
            .mapNotNull { index -> preview.topics.getOrNull(index) }

        return savePreviewTopics(
            previewId = request.previewId,
            sourceFileName = preview.sourceFileName,
            text = preview.text,
            topics = selectedTopics,
        )
    }

    private fun savePreviewTopics(
        previewId: String,
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
    ): TopicAnalysisSaveResult {
        if (topics.isEmpty()) {
            return TopicAnalysisSaveResult(previewId = previewId, topics = emptyList())
        }

        val parsed = KakaoMessageParser.parse(sourceFileName, text)
        val imported = importService.import(sourceFileName, text)
        val refToStoredId = parsed.mapIndexed { index, message ->
            index + 1 to imported.messages.first { it.fingerprint == message.fingerprint }.id
        }.toMap()

        val savedTopics = topics.map { topic ->
            topicRepository.createTopic(topic.remapEvidenceRefs(refToStoredId))
        }
        savedTopics.forEach(topicClaimSearchIndex::index)

        return TopicAnalysisSaveResult(
            previewId = previewId,
            topics = savedTopics,
        )
    }

    private fun TopicDraft.toNewTopicCandidate(document: SourceDocument): TopicCandidate =
        TopicCandidate(
            sourceType = document.sourceType,
            sourceName = document.sourceName,
            title = title,
            summary = summary,
            memoryTypes = memoryTypes,
            domains = domains,
            evidenceRefs = evidence.map { it.ref },
            claims = claims.map { claim ->
                TopicClaimCandidate(
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidence.map { it.ref },
                )
            },
        )

    private fun TopicCandidate.remapEvidenceRefs(refToStoredId: Map<Int, Int>): TopicCandidate =
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
