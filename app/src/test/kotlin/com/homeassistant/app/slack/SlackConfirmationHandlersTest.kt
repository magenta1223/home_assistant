package com.homeassistant.app.slack

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSelectionSaveRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SlackConfirmationHandlersTest {
    @Test
    fun `uploader can open review modal`() {
        val sessions = FakeSessionStore(session(ownerSlackUserId = "U1"))
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.buildReviewModal(previewId = "preview-1", actingSlackUserId = "U1")

        val openModal = assertIs<SlackReviewActionResult.OpenModal>(result)
        assertEquals("preview-1", openModal.view["private_metadata"])
    }

    @Test
    fun `other user cannot open review modal`() {
        val sessions = FakeSessionStore(session(ownerSlackUserId = "U1"))
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.buildReviewModal(previewId = "preview-1", actingSlackUserId = "U2")

        assertIs<SlackReviewActionResult.Ephemeral>(result)
    }

    @Test
    fun `completed session cannot open review modal`() {
        val sessions = FakeSessionStore(session(status = SlackTopicReviewStatus.COMPLETED))
        val handlers = SlackConfirmationHandlers(FakeTopicAnalysis, sessions)

        val result = handlers.buildReviewModal(previewId = "preview-1", actingSlackUserId = "U1")

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
            actingSlackUserId = "U1",
        )

        val saved = assertIs<SlackReviewSubmitResult.Saved>(result)
        assertEquals(2, saved.savedTopicCount)
        assertEquals("preview-1", FakeTopicAnalysis.selectionRequest.previewId)
        assertEquals(setOf(0, 2), FakeTopicAnalysis.selectionRequest.selectedTopicIndices)
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
            actingSlackUserId = "U1",
        )

        val saved = assertIs<SlackReviewSubmitResult.Saved>(result)
        assertEquals(0, saved.savedTopicCount)
        assertEquals(emptySet(), FakeTopicAnalysis.selectionRequest.selectedTopicIndices)
    }

    private fun session(
        ownerSlackUserId: String = "U1",
        status: SlackTopicReviewStatus = SlackTopicReviewStatus.AWAITING_CONFIRMATION,
    ) = SlackTopicReviewSession(
        previewId = "preview-1",
        ownerSlackUserId = ownerSlackUserId,
        status = status,
        topics = listOf(topic(1), topic(2), topic(3)),
    )
}

private class FakeSessionStore(
    session: SlackTopicReviewSession,
) : SlackTopicReviewSessionStore {
    private var session: SlackTopicReviewSession? = session

    override fun find(previewId: String): SlackTopicReviewSession? =
        session?.takeIf { it.previewId == previewId }

    override fun markCompleted(previewId: String) {
        session = session?.copy(status = SlackTopicReviewStatus.COMPLETED)
    }
}

private object FakeTopicAnalysis : TopicAnalysisUseCase() {
    lateinit var selectionRequest: TopicAnalysisSelectionSaveRequest

    fun reset() {
        selectionRequest = TopicAnalysisSelectionSaveRequest("", emptySet())
    }

    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("not used")

    override suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult =
        error("not used")

    override suspend fun saveSelectedAnalysis(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult {
        selectionRequest = request
        return TopicAnalysisSaveResult(
            previewId = request.previewId,
            topics = request.selectedTopicIndices.mapIndexed { index, _ -> persistedTopic(index) },
        )
    }
}

private fun topic(id: Int) =
    TopicCandidate(
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = "후보 $id",
        summary = "요약 $id",
        memoryTypes = listOf(MemoryType.STATE),
        domains = listOf("family"),
        evidenceRefs = listOf(id),
        claims = listOf(
            TopicClaimCandidate(
                text = "claim $id",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = ClaimCertainty.OBSERVED,
                evidenceRefs = listOf(id),
            ),
        ),
    )

private fun persistedTopic(id: Int) =
    Topic(
        id = id,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = "후보 $id",
        summary = "요약 $id",
        memoryTypes = listOf(MemoryType.STATE),
        domains = listOf("family"),
        evidenceRefs = listOf(id),
        claims = listOf(
            TopicClaim(
                id = id,
                text = "claim $id",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = ClaimCertainty.OBSERVED,
                evidenceRefs = listOf(id),
            ),
        ),
        status = CandidateStatus.PENDING,
    )
