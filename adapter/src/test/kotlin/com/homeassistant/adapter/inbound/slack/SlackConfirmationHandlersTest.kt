package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.slackconversation.SlackPrincipal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SlackConfirmationHandlersTest {
    @Test
    fun `uploader can open review modal`() {
        val sessions = FakeSessionStore(session(principal = principal("U1")))
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.buildReviewModal(previewId = "preview-1", actingPrincipal = principal("U1"))

        val openModal = assertIs<SlackReviewActionResult.OpenModal>(result)
        assertEquals("preview-1", openModal.view["private_metadata"])
    }

    @Test
    fun `other user cannot open review modal`() {
        val sessions = FakeSessionStore(session(principal = principal("U1")))
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.buildReviewModal(previewId = "preview-1", actingPrincipal = principal("U2"))

        assertIs<SlackReviewActionResult.Ephemeral>(result)
    }

    @Test
    fun `completed session cannot open review modal`() {
        val sessions = FakeSessionStore(session(status = SlackTopicReviewStatus.COMPLETED))
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.buildReviewModal(previewId = "preview-1", actingPrincipal = principal("U1"))

        assertIs<SlackReviewActionResult.Ephemeral>(result)
    }

    @Test
    fun `modal submit saves selected topic indices and completes session`() = runBlocking {
        FakeTopicAnalysis.reset()
        val sessions = FakeSessionStore(session())
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.submitSelection(
            previewId = "preview-1",
            selectedTopicIndices = setOf(0, 2),
            actingPrincipal = principal("U1"),
        )

        val saved = assertIs<SlackReviewSubmitResult.Saved>(result)
        assertEquals(2, saved.savedTopicCount)
        assertEquals("preview-1", FakeTopicAnalysis.selectionRequest.previewId)
        assertEquals(setOf(0, 2), FakeTopicAnalysis.selectionRequest.selectedTopicIndices)
        assertEquals("dad", FakeTopicAnalysis.selectionRequest.userId)
        assertEquals(SlackTopicReviewStatus.COMPLETED, sessions.find("preview-1")?.status)
    }

    @Test
    fun `empty selection is treated as saving zero topics`() = runBlocking {
        FakeTopicAnalysis.reset()
        val sessions = FakeSessionStore(session())
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.submitSelection(
            previewId = "preview-1",
            selectedTopicIndices = emptySet(),
            actingPrincipal = principal("U1"),
        )

        val saved = assertIs<SlackReviewSubmitResult.Saved>(result)
        assertEquals(0, saved.savedTopicCount)
        assertEquals(emptySet(), FakeTopicAnalysis.selectionRequest.selectedTopicIndices)
    }

    private fun session(
        principal: SlackPrincipal = principal("U1"),
        status: SlackTopicReviewStatus = SlackTopicReviewStatus.AWAITING_CONFIRMATION,
    ) = SlackTopicReviewSession(
        previewId = "preview-1",
        principal = principal,
        status = status,
        topics = listOf(topic(1), topic(2), topic(3)),
    )

    private fun principal(slackUserId: String) =
        SlackPrincipal(
            "T1",
            slackUserId,
            com.homeassistant.domain.identity.UserId(if (slackUserId == "U1") "dad" else "mom"),
        )
}

private class FakeSessionStore(
    session: SlackTopicReviewSession,
) : SlackTopicReviewSessionStore {
    private var session: SlackTopicReviewSession? = session

    override fun save(session: SlackTopicReviewSession) {
        this.session = session
    }

    override fun find(previewId: String): SlackTopicReviewSession? =
        session?.takeIf { it.previewId == previewId }

    override fun markCompleted(previewId: String) {
        session = session?.copy(status = SlackTopicReviewStatus.COMPLETED)
    }
}

private object FakeTopicAnalysis : SaveAnalyzedTopicsUseCase {
    lateinit var selectionRequest: TopicAnalysisSelectionSaveRequest

    fun reset() {
        selectionRequest = TopicAnalysisSelectionSaveRequest("", "dad", emptySet())
    }

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        error("not used")

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
                visibility = com.homeassistant.domain.memory.MemoryVisibility.FAMILY,
                evidenceRefs = listOf(id),
            ),
        ),
    )
