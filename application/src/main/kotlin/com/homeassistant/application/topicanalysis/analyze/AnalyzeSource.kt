package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.ProposedMemory
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.domain.topicanalysis.TopicDraft

interface AnalyzeSourceUseCase {
    suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult
}

class AnalyzeSource(
    private val topicExtractor: TopicExtractor,
    private val sourceTextParser: SourceTextParser,
    private val importService: KakaoImporter,
    private val previewRepository: TopicAnalysisPreviewStore,
    private val accessPolicy: HouseholdAccessPolicy,
) : AnalyzeSourceUseCase {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val messages = sourceTextParser.parse(request.sourceName, request.text)
        val newFingerprints = importService.findNewMessages(messages)
            .mapTo(mutableSetOf()) { it.fingerprint }
        if (messages.isNotEmpty() && newFingerprints.isEmpty()) {
            throw DuplicateKakaoMessagesException(request.sourceName, messages.size)
        }
        val includedFingerprints = mutableSetOf<String>()
        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = request.sourceName,
            records = messages.mapIndexedNotNull { index, message ->
                if (message.fingerprint !in newFingerprints || !includedFingerprints.add(message.fingerprint)) {
                    return@mapIndexedNotNull null
                }
                val recordNumber = index + 1
                SourceRecord(
                    id = "r$recordNumber",
                    ref = recordNumber,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
        val topics = topicExtractor.analyze(document).topics.map { topic ->
            topic.toProposal(document, userId)
        }
        val preview = previewRepository.createPreview(request.sourceName, request.text, topics)
        return TopicAnalysisResult(
            previewId = preview.previewId,
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = document.records.size,
            topics = topics,
        )
    }

    private fun TopicDraft.toProposal(
        document: SourceDocument,
        userId: UserId,
    ): ProposedTopic =
        ProposedTopic(
            createdByUserId = userId.value,
            sourceType = document.sourceType,
            sourceName = document.sourceName,
            title = title,
            summary = summary,
            memoryTypes = memoryTypes,
            categories = categories,
            evidenceRefs = evidence.map { it.ref },
            memories = claims.map { claim ->
                ProposedMemory(
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidence.map { it.ref },
                )
            },
        )

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }
}
