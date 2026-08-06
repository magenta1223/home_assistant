package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceRecordStore
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.ProposedMemory
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.indexing.IndexingOutboxes
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreview
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

class KakaoAnalyzeSourceIntegrationTest {
    @Test
    fun `analyze skips llm and preview when every message fingerprint already exists`() = runBlocking {
        val text = kakaoText()
        val parsed = KakaoExportParser.parse("family-kakao.txt", text)
        val extractor = RecordingTopicExtractor()
        val previewStore = RecordingPreviewStore()
        val useCase = AnalyzeSource(
            topicExtractor = extractor,
            sourceTextParser = KakaoExportParser,
            sourceRecords = FakeSourceRecordStore(
                parsed.mapTo(mutableSetOf()) { it.deduplicationKey },
            ),
            previewRepository = previewStore,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val error = assertFailsWith<DuplicateSourceRecordsException> {
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
    ): TopicAnalysisPreview =
        error("not used")

    override fun findPreview(previewId: String): TopicAnalysisPreview? =
        TopicAnalysisPreview(
            previewId = previewId,
            sourceName = "family-kakao.txt",
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

internal class FakeSourceRecordStore(
    private val existingKeys: Set<String> = emptySet(),
) : SourceRecordStore {
    private val records = mutableListOf<SourceRecord>()
    var saveCalls = 0

    override fun findExistingDeduplicationKeys(sourceType: String, keys: Set<String>): Set<String> =
        (existingKeys + records.map { it.deduplicationKey }).filterTo(mutableSetOf()) { it in keys }

    override fun saveAll(records: List<SourceRecordDraft>): List<SourceRecord> {
        saveCalls += 1
        return records.mapIndexed { index, record ->
            SourceRecord(
                id = index + 101,
                sourceType = record.sourceType,
                sourceName = record.sourceName,
                deduplicationKey = record.deduplicationKey,
                content = record.content,
            ).also(this.records::add)
        }
    }

    override fun findBySource(sourceType: String, sourceName: String): List<SourceRecord> =
        records.filter { it.sourceType == sourceType && it.sourceName == sourceName }
}

internal class RecordingPreviewStore : TopicAnalysisPreviewStore {
    var createCalls = 0

    override fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<ProposedTopic>,
    ): TopicAnalysisPreview {
        createCalls += 1
        return TopicAnalysisPreview("preview-1", sourceFileName, text, topics)
    }

    override fun findPreview(previewId: String): TopicAnalysisPreview? = null
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
