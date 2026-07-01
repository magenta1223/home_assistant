package com.homeassistant.app.slack

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackKakaoAnalysisWorkflowTest {
    @Test
    fun `process downloads file analyzes kakao text stores review session and posts approval message`() = runBlocking {
        val slack = FakeSlackClient(downloadedText = "kakao export")
        val analyzer = FakeAnalyzer()
        val sessions = InMemorySlackTopicReviewSessionStore()
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slack,
            topicAnalysis = analyzer,
            reviewSessions = sessions,
            maxFileSizeBytes = 10_485_760,
        )

        workflow.process(upload())

        assertEquals("kakao export", analyzer.text)
        assertEquals("kakao.txt", analyzer.sourceName)
        assertEquals(SlackTopicReviewStatus.AWAITING_CONFIRMATION, sessions.find("preview-1")?.status)
        assertEquals("U1", sessions.find("preview-1")?.ownerSlackUserId)
        assertEquals(2, slack.messages.size)
        assertEquals("D1", slack.messages.last().channelId)
        assertEquals("1710000000.000100", slack.messages.last().threadTs)
    }

    @Test
    fun `process posts ephemeral error when analysis fails`() = runBlocking {
        val slack = FakeSlackClient(downloadedText = "kakao export")
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slack,
            topicAnalysis = FailingAnalyzer,
            reviewSessions = InMemorySlackTopicReviewSessionStore(),
            maxFileSizeBytes = 10_485_760,
        )

        workflow.process(upload())

        assertEquals(1, slack.ephemeralMessages.size)
        assertEquals("U1", slack.ephemeralMessages.single().userId)
    }

    private fun upload() =
        SlackKakaoFileUpload(
            slackUserId = "U1",
            channelId = "D1",
            messageTs = "1710000000.000100",
            fileName = "kakao.txt",
            downloadUrl = "https://slack/files/kakao.txt",
        )
}

private class FakeSlackClient(
    private val downloadedText: String,
) : SlackClient {
    val messages = mutableListOf<PostedMessage>()
    val ephemeralMessages = mutableListOf<EphemeralMessage>()

    override fun downloadText(url: String, maxBytes: Long): String =
        downloadedText

    override fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ) {
        messages += PostedMessage(channelId, text, threadTs)
    }

    override fun postEphemeral(channelId: String, userId: String, text: String) {
        ephemeralMessages += EphemeralMessage(channelId, userId, text)
    }

    override fun openModal(triggerId: String, view: Map<String, Any>) {
        error("not used")
    }
}

private data class PostedMessage(
    val channelId: String,
    val text: String,
    val threadTs: String?,
)

private data class EphemeralMessage(
    val channelId: String,
    val userId: String,
    val text: String,
)

private class FakeAnalyzer : TopicAnalysisUseCase() {
    var sourceName = ""
    var text = ""

    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult {
        sourceName = request.sourceName
        text = request.text
        return TopicAnalysisResult(
            previewId = "preview-1",
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = 1,
            topics = listOf(topic()),
        )
    }

    override suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult =
        TopicAnalysisSaveResult(previewId, emptyList<Topic>())
}

private object FailingAnalyzer : TopicAnalysisUseCase() {
    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("analysis failed")

    override suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult =
        error("not used")
}

private fun topic() =
    TopicCandidate(
        sourceType = "kakao",
        sourceName = "kakao.txt",
        title = "이사 준비",
        summary = "관리사무소 질문을 모았다.",
        memoryTypes = listOf(MemoryType.STATE),
        domains = listOf("family"),
        evidenceRefs = listOf(1),
        claims = listOf(
            TopicClaimCandidate(
                text = "claim",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = ClaimCertainty.OBSERVED,
                evidenceRefs = listOf(1),
            ),
        ),
    )
