package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.topicanalysis.review.GetTopicAnalysisReviewRequest
import com.homeassistant.application.topicanalysis.review.GetTopicAnalysisReviewUseCase
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReview
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicProposal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SlackConfirmationHandlersTest {
    @Test
    fun `uploader can open review modal`() {
        val handlers = handlers(contextStore(context()))

        val result = handlers.buildReviewModal(reviewId = "preview-1", actingPrincipal = principal("U1"))

        val openModal = assertIs<SlackReviewActionResult.OpenModal>(result)
        assertEquals("preview-1", openModal.view["private_metadata"])
    }

    @Test
    fun `other user cannot open review modal`() {
        val handlers = handlers(contextStore(context()))

        val result = handlers.buildReviewModal(reviewId = "preview-1", actingPrincipal = principal("U2"))

        assertIs<SlackReviewActionResult.Ephemeral>(result)
    }

    @Test
    fun `completed context cannot open review modal`() {
        val handlers = handlers(contextStore(context(status = SlackReviewStatus.COMPLETED)))

        val result = handlers.buildReviewModal(reviewId = "preview-1", actingPrincipal = principal("U1"))

        assertIs<SlackReviewActionResult.Ephemeral>(result)
    }

    @Test
    fun `modal submit saves selected topic indices and completes context`() = runBlocking {
        FakeTopicAnalysis.reset()
        val contexts = contextStore(context())
        val handlers = handlers(contexts)

        val result = handlers.submitSelection(
            reviewId = "preview-1",
            selectedTopicIndices = setOf(0, 2),
            actingPrincipal = principal("U1"),
        )

        val saved = assertIs<SlackReviewSubmitResult.Saved>(result)
        assertEquals(2, saved.savedTopicCount)
        assertEquals("preview-1", FakeTopicAnalysis.selectionRequest.previewId)
        assertEquals(setOf(0, 2), FakeTopicAnalysis.selectionRequest.selectedTopicIndices)
        assertEquals("dad", FakeTopicAnalysis.selectionRequest.userId)
        assertEquals(SlackReviewStatus.COMPLETED, contexts.find("preview-1")?.status)
    }

    @Test
    fun `empty selection is treated as saving zero topics`() = runBlocking {
        FakeTopicAnalysis.reset()
        val handlers = handlers(contextStore(context()))

        val result = handlers.submitSelection(
            reviewId = "preview-1",
            selectedTopicIndices = emptySet(),
            actingPrincipal = principal("U1"),
        )

        val saved = assertIs<SlackReviewSubmitResult.Saved>(result)
        assertEquals(0, saved.savedTopicCount)
        assertEquals(emptySet(), FakeTopicAnalysis.selectionRequest.selectedTopicIndices)
    }

    private fun handlers(contexts: SlackReviewContextStore) =
        SlackConfirmationHandlers(FakeTopicAnalysis, FakeGetReview, contexts)

    private fun contextStore(context: SlackReviewContext) =
        SlackReviewContextStore().also { it.save(context) }

    private fun context(status: SlackReviewStatus = SlackReviewStatus.AWAITING_CONFIRMATION) =
        SlackReviewContext("preview-1", status, "D1")

    private fun principal(slackUserId: String) =
        SlackPrincipal(
            "T1",
            slackUserId,
            UserId(if (slackUserId == "U1") "dad" else "mom"),
        )
}

private object FakeGetReview : GetTopicAnalysisReviewUseCase {
    override fun get(request: GetTopicAnalysisReviewRequest): TopicAnalysisReview {
        if (request.userId != "dad") throw HouseholdAccessDeniedException()
        return TopicAnalysisReview(
            id = request.reviewId,
            requestedBy = UserId("dad"),
            source = SourceDescriptor("kakao", "family-kakao.txt"),
            proposals = listOf(topic(1), topic(2), topic(3)),
        )
    }
}

private object FakeTopicAnalysis : SaveAnalyzedTopicsUseCase {
    lateinit var selectionRequest: TopicAnalysisSelectionSaveRequest

    fun reset() {
        selectionRequest = TopicAnalysisSelectionSaveRequest("", "dad", emptySet())
    }

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult = error("not used")

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        selectionRequest = request
        return TopicAnalysisSaveResult(
            previewId = request.previewId,
            topics = request.selectedTopicIndices.mapIndexed { index, _ -> persistedTopic(index) },
        )
    }
}

private fun topic(id: Int) =
    TopicProposal(
        title = "후보 $id",
        summary = "요약 $id",
        categories = listOf("family"),
        memories = listOf(
            MemoryProposal(
                content = "claim $id",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = MemoryCertainty.OBSERVED,
                evidenceIds = listOf(id),
            ),
        ),
    )

private fun persistedTopic(id: Int) =
    Topic(
        id = id,
        createdByUserId = "dad",
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = "후보 $id",
        summary = "요약 $id",
        categories = listOf("family"),
        memories = listOf(
            Memory(
                id = id,
                topicId = id,
                createdByUserId = "dad",
                content = "claim $id",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = MemoryCertainty.OBSERVED,
                visibility = MemoryVisibility.FAMILY,
                evidenceRefs = listOf(id),
            ),
        ),
    )
