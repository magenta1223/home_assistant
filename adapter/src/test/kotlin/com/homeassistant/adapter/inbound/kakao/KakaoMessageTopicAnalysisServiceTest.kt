package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.analyze.DuplicateKakaoMessagesException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.kakao.KakaoAnalysisPreview
import com.homeassistant.domain.kakao.KakaoMessage
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.ProposedMemory
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.domain.kakao.KakaoMessageStore
import com.homeassistant.domain.kakao.ParsedKakaoMessage
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.indexing.IndexingOutboxes
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.application.memory.answer.MemorySearchDocument
import com.homeassistant.application.memory.answer.MemorySearchHit
import com.homeassistant.application.memory.answer.MemorySearchIndex
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
        val useCase = AnalyzeSource(
            topicExtractor = extractor,
            sourceTextParser = KakaoExportParser,
            importService = KakaoImporterFactory.create(
                FakeKakaoMessageStore(parsed.mapTo(mutableSetOf()) { it.fingerprint }),
            ),
            previewRepository = previewStore,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val error = assertFailsWith<DuplicateKakaoMessagesException> {
            useCase.execute(request(text))
        }

        assertEquals(2, error.recordCount)
        assertEquals(0, extractor.calls)
        assertEquals(0, previewStore.createCalls)
    }

}

internal class FakePreviewStore(
    private val topics: List<ProposedTopic>,
) : TopicAnalysisPreviewStore {
    override fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<ProposedTopic>,
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
    val createdTopics = mutableListOf<ProposedTopic>()

    override fun createTopic(proposal: ProposedTopic): Topic {
        createdTopics += proposal
        return Topic(
            id = createdTopics.size,
            createdByUserId = proposal.createdByUserId,
            sourceType = proposal.sourceType,
            sourceName = proposal.sourceName,
            title = proposal.title,
            summary = proposal.summary,
            categories = proposal.categories,
            memories = proposal.memories.mapIndexed { index, memory ->
                Memory(
                    id = index + 1,
                    topicId = createdTopics.size,
                    createdByUserId = proposal.createdByUserId,
                    content = memory.text,
                    subject = memory.subject,
                    memoryType = memory.memoryType,
                    certainty = memory.certainty,
                    visibility = memory.visibility,
                    evidenceRefs = memory.evidenceRefs,
                )
            },
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

    override fun getTopicsForMemoryIndexing(memoryIds: Collection<Int>): List<Topic> =
        emptyList()
}

internal class RecordingMemorySearchIndex : MemorySearchIndex {
    val indexedDocuments = mutableListOf<MemorySearchDocument>()

    override fun index(document: MemorySearchDocument) {
        indexedDocuments += document
    }

    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<MemorySearchHit> =
        emptyList()
}

internal object FailingMemorySearchIndex : MemorySearchIndex {
    override fun index(document: MemorySearchDocument) = error("qdrant unavailable")
    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<MemorySearchHit> = emptyList()
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
        topics: List<ProposedTopic>,
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
    ProposedTopic(
        createdByUserId = TEST_USER.value,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = title,
        summary = "요약",
        memoryTypes = listOf(MemoryType.STATE),
        categories = listOf("family"),
        evidenceRefs = listOf(evidenceRef),
        memories = listOf(
            ProposedMemory(
                text = "claim",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = MemoryCertainty.OBSERVED,
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
