package com.homeassistant.adapter.inbound.slack

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.slackconversation.SlackPrincipal
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaimCandidate
import com.homeassistant.application.topicanalysis.analyze.DuplicateKakaoMessagesException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.TopicAnalysisUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
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
        assertEquals("U1", sessions.find("preview-1")?.principal?.slackUserId)
        assertEquals("dad", analyzer.userId)
        assertEquals("family-1", analyzer.familyId)
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

    @Test
    fun `process reports already analyzed kakao data without creating review session`() = runBlocking {
        val slack = FakeSlackClient(downloadedText = "kakao export")
        val sessions = InMemorySlackTopicReviewSessionStore()
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slack,
            topicAnalysis = DuplicateAnalyzer,
            reviewSessions = sessions,
            maxFileSizeBytes = 10_485_760,
        )

        workflow.process(upload())

        assertEquals(1, slack.ephemeralMessages.size)
        assertContains(slack.ephemeralMessages.single().text, "이미 분석된")
        assertEquals(null, sessions.find("preview-1"))
    }

    private fun upload() =
        SlackKakaoFileUpload(
            principal = SlackPrincipal("T1", "U1", "dad", "family-1"),
            channelId = "D1",
            messageTs = "1710000000.000100",
            fileId = null,
            fileName = "kakao.txt",
            downloadUrl = "https://slack/files/kakao.txt",
        )
}

private class FakeSlackClient(
    private val downloadedText: String,
) : SlackClient {
    val messages = mutableListOf<PostedMessage>()
    val ephemeralMessages = mutableListOf<EphemeralMessage>()

    override fun fileDownloadUrl(fileId: String): String? =
        null

    override fun downloadText(url: String, maxBytes: Long): String =
        downloadedText

    override fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ): SlackMessageDelivery {
        messages += PostedMessage(channelId, text, threadTs)
        return SlackMessageDelivery("${messages.size}.0")
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

private class FakeAnalyzer : TopicAnalysisUseCase {
    var sourceName = ""
    var text = ""
    var userId = ""
    var familyId = ""

    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult {
        sourceName = request.sourceName
        text = request.text
        userId = request.userId
        familyId = request.familyId
        return TopicAnalysisResult(
            previewId = "preview-1",
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = 1,
            topics = listOf(topic()),
        )
    }

    override suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        TopicAnalysisSaveResult(request.previewId, emptyList<Topic>())
}

private object FailingAnalyzer : TopicAnalysisUseCase {
    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("analysis failed")

    override suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        error("not used")
}

private object DuplicateAnalyzer : TopicAnalysisUseCase {
    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        throw DuplicateKakaoMessagesException(request.sourceName, 1)

    override suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        error("not used")
}

private fun topic() =
    TopicCandidate(
        familyId = "family-1",
        createdByUserId = "dad",
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
