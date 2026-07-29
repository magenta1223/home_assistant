package com.homeassistant.app.slack

import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSelectionSaveRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import com.homeassistant.domain.slackconversation.SlackPrincipal

interface SlackConfirmationHandler {
    fun buildReviewModal(previewId: String, actingPrincipal: SlackPrincipal): SlackReviewActionResult
    suspend fun submitSelection(
        previewId: String,
        selectedTopicIndices: Set<Int>,
        actingPrincipal: SlackPrincipal,
    ): SlackReviewSubmitResult
}

internal class SlackConfirmationHandlers(
    private val topicAnalysis: TopicAnalysisUseCase,
    private val reviewSessions: SlackTopicReviewSessionStore,
) : SlackConfirmationHandler {
    override fun buildReviewModal(
        previewId: String,
        actingPrincipal: SlackPrincipal,
    ): SlackReviewActionResult {
        val session = reviewSessions.find(previewId)
            ?: return SlackReviewActionResult.Ephemeral("검토 요청을 찾을 수 없습니다.")
        if (session.principal != actingPrincipal) {
            return SlackReviewActionResult.Ephemeral("업로드한 사용자만 이 후보를 검토할 수 있습니다.")
        }
        if (session.status != SlackTopicReviewStatus.AWAITING_CONFIRMATION) {
            return SlackReviewActionResult.Ephemeral("이미 처리되었거나 만료된 검토 요청입니다.")
        }

        return when (val modal = SlackTopicBlocks.selectionModal(previewId, session.topics)) {
            is SlackModalBuildResult.Modal -> SlackReviewActionResult.OpenModal(modal.view)
            is SlackModalBuildResult.TooManyTopics -> SlackReviewActionResult.Ephemeral(
                "후보가 ${modal.actualCount}개입니다. Slack 모달에서는 ${modal.maxCount}개까지만 검토할 수 있습니다.",
            )
        }
    }

    override suspend fun submitSelection(
        previewId: String,
        selectedTopicIndices: Set<Int>,
        actingPrincipal: SlackPrincipal,
    ): SlackReviewSubmitResult {
        val session = reviewSessions.find(previewId)
            ?: return SlackReviewSubmitResult.Rejected("검토 요청을 찾을 수 없습니다.")
        if (session.principal != actingPrincipal) {
            return SlackReviewSubmitResult.Rejected("업로드한 사용자만 이 후보를 승인할 수 있습니다.")
        }
        if (session.status != SlackTopicReviewStatus.AWAITING_CONFIRMATION) {
            return SlackReviewSubmitResult.Rejected("이미 처리되었거나 만료된 검토 요청입니다.")
        }

        val result = topicAnalysis.saveSelectedAnalysis(
            TopicAnalysisSelectionSaveRequest(
                previewId = previewId,
                userId = session.principal.userId.value,
                familyId = session.principal.familyId.value,
                selectedTopicIndices = selectedTopicIndices,
            ),
        )
        reviewSessions.markCompleted(previewId)
        return SlackReviewSubmitResult.Saved(result.topics.size)
    }
}

interface SlackTopicReviewSessionStore {
    fun save(session: SlackTopicReviewSession)
    fun find(previewId: String): SlackTopicReviewSession?
    fun markCompleted(previewId: String)
}

data class SlackTopicReviewSession(
    val previewId: String,
    val principal: SlackPrincipal,
    val status: SlackTopicReviewStatus,
    val channelId: String = "",
    val messageTs: String? = null,
    val topics: List<com.homeassistant.datamodel.topicanalysis.TopicCandidate>,
)

enum class SlackTopicReviewStatus {
    AWAITING_CONFIRMATION,
    COMPLETED,
}

sealed class SlackReviewActionResult {
    data class OpenModal(val view: Map<String, Any>) : SlackReviewActionResult()
    data class Ephemeral(val message: String) : SlackReviewActionResult()
}

sealed class SlackReviewSubmitResult {
    data class Saved(val savedTopicCount: Int) : SlackReviewSubmitResult()
    data class Rejected(val message: String) : SlackReviewSubmitResult()
}
