package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.tools.Tool
import com.homeassistant.datamodel.kakao.KakaoAnalysisPreview
import com.homeassistant.datamodel.kakao.KakaoMessage
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageStore
import com.homeassistant.domain.kakao.ParsedKakaoMessage
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSelectionSaveRequest
import com.homeassistant.nlp.topicanalysis.impl.KakaoMessageTopicAnalysisService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoMessageTopicAnalysisServiceTest {
    @Test
    fun `save selected analysis persists only selected preview topics`() = runBlocking {
        val kakaoStore = FakeKakaoMessageStore()
        val topicStore = FakeTopicStore()
        val previewStore = FakePreviewStore(
            topics = listOf(topic("첫 후보", 1), topic("둘째 후보", 2), topic("셋째 후보", 3)),
        )
        val service = KakaoMessageTopicAnalysisService(
            backend = UnusedBackend,
            importService = KakaoImportService(kakaoStore),
            topicRepository = topicStore,
            previewRepository = previewStore,
        )

        val result = service.saveSelectedAnalysis(
            TopicAnalysisSelectionSaveRequest(
                previewId = "preview-1",
                selectedTopicIndices = setOf(2, 0, 99),
            ),
        )

        assertEquals(listOf("첫 후보", "셋째 후보"), result.topics.map { it.title })
        assertEquals(listOf("첫 후보", "셋째 후보"), topicStore.createdTopics.map { it.title })
        assertEquals(1, kakaoStore.importCalls)
    }

    @Test
    fun `save selected analysis with empty selection does not import kakao messages`() = runBlocking {
        val kakaoStore = FakeKakaoMessageStore()
        val service = KakaoMessageTopicAnalysisService(
            backend = UnusedBackend,
            importService = KakaoImportService(kakaoStore),
            topicRepository = FakeTopicStore(),
            previewRepository = FakePreviewStore(topics = listOf(topic("첫 후보", 1))),
        )

        val result = service.saveSelectedAnalysis(
            TopicAnalysisSelectionSaveRequest(
                previewId = "preview-1",
                selectedTopicIndices = emptySet(),
            ),
        )

        assertEquals(emptyList(), result.topics)
        assertEquals(0, kakaoStore.importCalls)
    }
}

private class FakePreviewStore(
    private val topics: List<TopicCandidate>,
) : TopicAnalysisPreviewStore {
    override fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
    ): KakaoAnalysisPreview =
        error("not used")

    override fun findPreview(previewId: String): KakaoAnalysisPreview? =
        KakaoAnalysisPreview(
            previewId = previewId,
            sourceFileName = "family-kakao.txt",
            text = """
                2026년 6월 15일 오전 6:43
                2026년 6월 15일 오전 6:43, 동훈 : 첫 메시지
                2026년 6월 15일 오전 6:44, 승민 : 둘째 메시지
                2026년 6월 15일 오전 6:45, 동훈 : 셋째 메시지
            """.trimIndent(),
            topics = topics,
        )
}

private class FakeTopicStore : TopicAnalysisStore {
    val createdTopics = mutableListOf<TopicCandidate>()

    override fun createTopic(candidate: TopicCandidate): Topic {
        createdTopics += candidate
        return Topic(
            id = createdTopics.size,
            sourceType = candidate.sourceType,
            sourceName = candidate.sourceName,
            title = candidate.title,
            summary = candidate.summary,
            memoryTypes = candidate.memoryTypes,
            domains = candidate.domains,
            evidenceRefs = candidate.evidenceRefs,
            claims = candidate.claims.mapIndexed { index, claim ->
                TopicClaim(
                    id = index + 1,
                    text = claim.text,
                    subject = claim.subject,
                    memoryType = claim.memoryType,
                    certainty = claim.certainty,
                    evidenceRefs = claim.evidenceRefs,
                )
            },
            status = CandidateStatus.PENDING,
        )
    }
}

private class FakeKakaoMessageStore : KakaoMessageStore {
    private var messages = emptyList<KakaoMessage>()
    var importCalls = 0

    override fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoMessage> {
        importCalls += 1
        this.messages = messages.mapIndexed { index, message ->
            KakaoMessage(
                id = index + 101,
                sourceFileName = message.sourceFileName,
                sender = message.sender,
                displayTime = message.displayTime,
                text = message.text,
                lineStart = message.lineStart,
                lineEnd = message.lineEnd,
                fingerprint = message.fingerprint,
            )
        }
        return this.messages
    }

    override fun listMessages(sourceFileName: String): List<KakaoMessage> =
        messages
}

private object UnusedBackend : LlmBackend {
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse =
        error("not used")
}

private fun topic(title: String, evidenceRef: Int) =
    TopicCandidate(
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = title,
        summary = "요약",
        memoryTypes = listOf(MemoryType.STATE),
        domains = listOf("family"),
        evidenceRefs = listOf(evidenceRef),
        claims = listOf(
            TopicClaimCandidate(
                text = "claim",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = ClaimCertainty.OBSERVED,
                evidenceRefs = listOf(evidenceRef),
            ),
        ),
    )
