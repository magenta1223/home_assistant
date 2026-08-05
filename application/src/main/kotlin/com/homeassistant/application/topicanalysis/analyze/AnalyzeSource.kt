package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.core.identity.FamilyId
import com.homeassistant.core.identity.HouseholdAccessDeniedException
import com.homeassistant.core.identity.HouseholdAccessPolicy
import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.core.identity.UserId
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaimCandidate
import com.homeassistant.domain.topicanalysis.TopicDraft

internal class AnalyzeSource(
    private val topicExtractor: TopicExtractor,
    private val importService: KakaoImporter,
    private val previewRepository: TopicAnalysisPreviewStore,
    private val accessPolicy: HouseholdAccessPolicy,
) {
    suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        val scope = request.scope()
        requireAuthorized(scope)
        val messages = KakaoMessageParser.parse(request.sourceName, request.text)
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
            topic.toCandidate(document, scope)
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

    private fun TopicDraft.toCandidate(
        document: SourceDocument,
        scope: HouseholdAccessScope,
    ): TopicCandidate =
        TopicCandidate(
            familyId = scope.familyId.value,
            createdByUserId = scope.userId.value,
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

    private fun TopicAnalysisRequest.scope(): HouseholdAccessScope =
        HouseholdAccessScope(UserId(userId), FamilyId(familyId))

    private fun requireAuthorized(scope: HouseholdAccessScope) {
        if (!accessPolicy.isAuthorized(scope)) throw HouseholdAccessDeniedException()
    }
}
