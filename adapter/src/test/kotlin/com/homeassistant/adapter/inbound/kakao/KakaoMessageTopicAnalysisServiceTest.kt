package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.TopicAnalysisFactory
import com.homeassistant.application.topicanalysis.analyze.DuplicateKakaoMessagesException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.kakao.KakaoAnalysisPreview
import com.homeassistant.domain.kakao.KakaoMessage
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicClaimCandidate
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.domain.kakao.KakaoMessageStore
import com.homeassistant.domain.kakao.ParsedKakaoMessage
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.indexing.IndexingOutboxes
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.application.topicanswer.answer.TopicClaimSearchHit
import com.homeassistant.application.topicanswer.answer.TopicClaimSearchIndex
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KakaoMessageTopicAnalysisServiceTest {
    @Test
    fun `analyze skips llm and preview when every message fingerprint already exists`() = runBlocking {
        val text = kakaoText()
        val parsed = KakaoExportParser.parse("family-kakao.txt", text)
        val extractor = RecordingTopicExtractor()
        val previewStore = RecordingPreviewStore()
        val service = TopicAnalysisFactory.kakao(
            topicExtractor = extractor,
            sourceTextParser = KakaoExportParser,
            importer = KakaoImporterFactory.create(
                FakeKakaoMessageStore(parsed.mapTo(mutableSetOf()) { it.fingerprint }),
            ),
            topicStore = FakeTopicStore(),
            previewStore = previewStore,
            indexingOutbox = IndexingOutboxes.noOp(),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val error = assertFailsWith<DuplicateKakaoMessagesException> {
            service.analyzeSource.execute(request(text))
        }

        assertEquals(2, error.recordCount)
        assertEquals(0, extractor.calls)
        assertEquals(0, previewStore.createCalls)
    }

}

internal class FakePreviewStore(
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

internal class FakeTopicStore : TopicAnalysisStore {
    val createdTopics = mutableListOf<TopicCandidate>()

    override fun createTopic(candidate: TopicCandidate): Topic {
        createdTopics += candidate
        return Topic(
            id = createdTopics.size,
            familyId = candidate.familyId,
            createdByUserId = candidate.createdByUserId,
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

    override fun searchApprovedTopics(
        userId: UserId,
        query: String,
        limit: Int,
    ): List<Topic> =
        emptyList()

    override fun getApprovedTopics(
        userId: UserId,
        topicIds: Collection<Int>,
    ): List<Topic> =
        emptyList()

    override fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic> =
        emptyList()
}

internal class RecordingTopicClaimSearchIndex : TopicClaimSearchIndex {
    val indexedTopics = mutableListOf<Topic>()

    override fun index(topic: Topic) {
        indexedTopics += topic
    }

    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<TopicClaimSearchHit> =
        emptyList()
}

internal object FailingTopicClaimSearchIndex : TopicClaimSearchIndex {
    override fun index(topic: Topic) = error("qdrant unavailable")
    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<TopicClaimSearchHit> = emptyList()
}

internal class FakeIndexingOutboxStore : IndexingOutboxStore {
    private val pending = mutableMapOf<IndexTargetType, MutableSet<Int>>()

    override fun pending(targetType: IndexTargetType, limit: Int): List<Int> =
        pending[targetType].orEmpty().take(limit)

    override fun markIndexed(targetType: IndexTargetType, targetId: Int) {
        pending[targetType]?.remove(targetId)
    }

    override fun markFailed(targetType: IndexTargetType, targetId: Int, error: String) {
        pending.getOrPut(targetType) { linkedSetOf() }.add(targetId)
    }
}

internal class FakeKakaoMessageStore(
    private val existingFingerprints: Set<String> = emptySet(),
) : KakaoMessageStore {
    private var messages = emptyList<KakaoMessage>()
    var importCalls = 0

    override fun findExistingFingerprints(fingerprints: Set<String>): Set<String> =
        (existingFingerprints + messages.map { it.fingerprint })
            .filterTo(mutableSetOf()) { it in fingerprints }

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

internal class RecordingPreviewStore : TopicAnalysisPreviewStore {
    var createCalls = 0

    override fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
    ): KakaoAnalysisPreview {
        createCalls += 1
        return KakaoAnalysisPreview("preview-1", sourceFileName, text, topics)
    }

    override fun findPreview(previewId: String): KakaoAnalysisPreview? = null
}

internal class RecordingTopicExtractor : TopicExtractor {
    var calls = 0
    var document: SourceDocument? = null

    override suspend fun analyze(document: SourceDocument): com.homeassistant.domain.topicanalysis.TopicAnalysisResult {
        calls += 1
        this.document = document
        return com.homeassistant.domain.topicanalysis.TopicAnalysisResult(emptyList())
    }
}

internal object UnusedTopicExtractor : TopicExtractor {
    override suspend fun analyze(document: SourceDocument): com.homeassistant.domain.topicanalysis.TopicAnalysisResult =
        error("not used")
}

internal fun kakaoText(): String =
    """
    2026년 6월 15일 오전 6:43, 동훈 : 첫 메시지
    2026년 6월 15일 오전 6:44, 승민 : 둘째 메시지
    """.trimIndent()

internal fun topic(title: String, evidenceRef: Int) =
    TopicCandidate(
        familyId = "household",
        createdByUserId = TEST_USER.value,
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

internal fun request(text: String): TopicAnalysisRequest =
    TopicAnalysisRequest(
        userId = TEST_USER.value,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        text = text,
    )

internal val TEST_USER = UserId("dad")
internal val TEST_ACCESS_POLICY = HouseholdAccessPolicy { it == TEST_USER }
