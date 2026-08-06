package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.topicanalysis.review.GetTopicAnalysisReviewRequest
import com.homeassistant.application.topicanalysis.review.GetTopicAnalysisReviewUseCase
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewNotFoundException
import com.homeassistant.domain.identity.HouseholdAccessDeniedException

interface SlackConfirmationHandler {
    fun buildReviewModal(reviewId: String, actingPrincipal: SlackPrincipal): SlackReviewActionResult
    suspend fun submitSelection(
        reviewId: String,
        selectedTopicIndices: Set<Int>,
        actingPrincipal: SlackPrincipal,
    ): SlackReviewSubmitResult
}

internal class SlackConfirmationHandlers(
    private val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
    private val getReview: GetTopicAnalysisReviewUseCase,
    private val reviewContexts: SlackReviewContextStore,
) : SlackConfirmationHandler {
    override fun buildReviewModal(
        reviewId: String,
        actingPrincipal: SlackPrincipal,
    ): SlackReviewActionResult {
        val context = reviewContexts.find(reviewId)
            ?: return SlackReviewActionResult.Ephemeral("검토 요청을 찾을 수 없습니다.")
        if (context.status != SlackReviewStatus.AWAITING_CONFIRMATION) {
            return SlackReviewActionResult.Ephemeral("이미 처리되었거나 만료된 검토 요청입니다.")
        }
        val review = try {
            getReview.get(GetTopicAnalysisReviewRequest(reviewId, actingPrincipal.userId.value))
        } catch (_: HouseholdAccessDeniedException) {
            return SlackReviewActionResult.Ephemeral("업로드한 사용자만 이 후보를 검토할 수 있습니다.")
        } catch (_: TopicAnalysisReviewNotFoundException) {
            return SlackReviewActionResult.Ephemeral("검토 요청을 찾을 수 없습니다.")
        }

        return when (val modal = SlackTopicBlocks.selectionModal(reviewId, review.proposals)) {
            is SlackModalBuildResult.Modal -> SlackReviewActionResult.OpenModal(modal.view)
            is SlackModalBuildResult.TooManyTopics -> SlackReviewActionResult.Ephemeral(
                "후보가 ${modal.actualCount}개입니다. Slack 모달에서는 ${modal.maxCount}개까지만 검토할 수 있습니다.",
            )
        }
    }

    override suspend fun submitSelection(
        reviewId: String,
        selectedTopicIndices: Set<Int>,
        actingPrincipal: SlackPrincipal,
    ): SlackReviewSubmitResult {
        val context = reviewContexts.find(reviewId)
            ?: return SlackReviewSubmitResult.Rejected("검토 요청을 찾을 수 없습니다.")
        if (context.status != SlackReviewStatus.AWAITING_CONFIRMATION) {
            return SlackReviewSubmitResult.Rejected("이미 처리되었거나 만료된 검토 요청입니다.")
        }
        try {
            getReview.get(GetTopicAnalysisReviewRequest(reviewId, actingPrincipal.userId.value))
        } catch (_: HouseholdAccessDeniedException) {
            return SlackReviewSubmitResult.Rejected("업로드한 사용자만 이 후보를 승인할 수 있습니다.")
        } catch (_: TopicAnalysisReviewNotFoundException) {
            return SlackReviewSubmitResult.Rejected("검토 요청을 찾을 수 없습니다.")
        }

        val result = saveAnalyzedTopics.saveSelected(
            TopicAnalysisSelectionSaveRequest(
                previewId = reviewId,
                userId = actingPrincipal.userId.value,
                selectedTopicIndices = selectedTopicIndices,
            ),
        )
        reviewContexts.markCompleted(reviewId)
        return SlackReviewSubmitResult.Saved(result.topics.size)
    }
}

interface SlackReviewContextStore {
    fun save(context: SlackReviewContext)
    fun find(reviewId: String): SlackReviewContext?
    fun markCompleted(reviewId: String)
}

data class SlackReviewContext(
    val reviewId: String,
    val status: SlackReviewStatus,
    val channelId: String,
)

enum class SlackReviewStatus {
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
