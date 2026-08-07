package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SlackKakaoAnalysisWorkflowTest {
    @Test
    fun `process downloads file analyzes and posts saved topic message`() = runBlocking {
        val slack = FakeSlackClient(downloadedText = "[동훈] [오후 4:49] kakao export")
        val analyzer = FakeAnalyzer()
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slack,
            topicAnalysis = analyzer,
            maxFileSizeBytes = 10_485_760,
        )

        workflow.process(upload())

        assertEquals("동훈 | 오후 4:49 | kakao export", analyzer.text)
        assertEquals("kakao.txt", analyzer.sourceName)
        assertEquals("dad", analyzer.userId)
        assertEquals(2, slack.messages.size)
        assertEquals("D1", slack.messages.last().channelId)
        assertEquals("1710000000.000100", slack.messages.last().threadTs)
        assertContains(slack.messages.last().text, "분석 및 저장 완료")
    }

    @Test
    fun `process posts ephemeral error when analysis fails`() = runBlocking {
        val slack = FakeSlackClient(downloadedText = "[동훈] [오후 4:49] kakao export")
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slack,
            topicAnalysis = FailingAnalyzer,
            maxFileSizeBytes = 10_485_760,
        )

        workflow.process(upload())

        assertEquals(1, slack.ephemeralMessages.size)
        assertEquals("U1", slack.ephemeralMessages.single().userId)
    }

    @Test
    fun `process reports already analyzed kakao data`() = runBlocking {
        val slack = FakeSlackClient(downloadedText = "[동훈] [오후 4:49] kakao export")
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slack,
            topicAnalysis = DuplicateAnalyzer,
            maxFileSizeBytes = 10_485_760,
        )

        workflow.process(upload())

        assertEquals(1, slack.ephemeralMessages.size)
        assertContains(slack.ephemeralMessages.single().text, "이미 분석된")
    }

    private fun upload() =
        SlackKakaoFileUpload(
            principal = SlackPrincipal("T1", "U1", UserId("dad")),
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

    override fun fileDownloadUrl(fileId: String): String? = null

    override fun downloadText(url: String, maxBytes: Long): String = downloadedText

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

private class FakeAnalyzer : TopicAnalysis {
    var sourceName = ""
    var text = ""
    var userId = ""

    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        sourceName = request.source.source.name
        text = request.source.records.singleOrNull()?.content.orEmpty()
        userId = request.userId
        return TopicAnalysisResult(
            sourceType = request.source.source.type,
            sourceName = request.source.source.name,
            importedRecordCount = 1,
            topics = listOf(topic()),
        )
    }
}

private object FailingAnalyzer : TopicAnalysis {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("analysis failed")
}

private object DuplicateAnalyzer : TopicAnalysis {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult =
        throw DuplicateSourceRecordsException(request.source.source.name, 1)
}

private fun topic() =
    TopicProposal(
        title = "이사 준비",
        summary = "관리사무소 질문을 모았다.",
        categories = listOf("family"),
        memories = listOf(
            MemoryProposal(
                content = "claim",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = MemoryCertainty.OBSERVED,
                evidenceIds = listOf(1),
            ),
        ),
    )
