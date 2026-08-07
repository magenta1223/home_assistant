package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisService
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.application.topicanalysis.save.IndexTargetType
import com.homeassistant.application.topicanalysis.save.IndexingOutboxStore
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReview
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewStore
import com.homeassistant.application.memory.CanonicalMemoryContext
import com.homeassistant.application.memory.index.MemoryIndexer
import com.homeassistant.application.memory.index.MemoryIndexingSource
import com.homeassistant.application.topicanalysis.save.TopicCreator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KakaoTopicAnalysisServiceIntegrationTest {
    @Test
    fun `analyze skips llm and preview when every message fingerprint already exists`() = runBlocking {
        val text = kakaoText()
        val parsed = KakaoExportParser.parse("family-kakao.txt", text)
        val extractor = RecordingTopicExtractor()
        val reviewStore = RecordingReviewStore()
        val useCase = TopicAnalysisService(
            topicExtractor = extractor,
            sourceRecords = FakeSourceRecordRepository(
                parsed.records.mapTo(mutableSetOf()) { it.deduplicationKey },
            ),
            reviewStore = reviewStore,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val error = assertFailsWith<DuplicateSourceRecordsException> {
            useCase.execute(request(text))
        }

        assertEquals(2, error.recordCount)
        assertEquals(0, extractor.calls)
        assertEquals(0, reviewStore.createCalls)
    }

}

internal class FakeReviewStore(
    private val topics: List<TopicProposal>,
) : TopicAnalysisReviewStore {
    override fun create(
        requestedBy: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): TopicAnalysisReview =
        error("not used")

    override fun find(reviewId: String): TopicAnalysisReview? =
        TopicAnalysisReview(
            id = reviewId,
            requestedBy = TEST_USER,
            source = SourceDescriptor("kakao", "family-kakao.txt"),
            proposals = topics,
        )
}

internal class FakeTopicStore : TopicCreator {
    val createdTopics = mutableListOf<TopicProposal>()

    override fun create(
        proposal: TopicProposal,
        createdBy: UserId,
        source: SourceDescriptor,
    ): Topic {
        createdTopics += proposal
        return Topic(
            id = createdTopics.size,
            createdByUserId = createdBy.value,
            sourceType = source.type,
            sourceName = source.name,
            title = proposal.title,
            summary = proposal.summary,
            categories = proposal.categories,
            memories = proposal.memories.mapIndexed { index, memory ->
                Memory(
                    id = index + 1,
                    topicId = createdTopics.size,
                    createdByUserId = createdBy.value,
                    content = memory.content,
                    subject = memory.subject,
                    memoryType = memory.memoryType,
                    certainty = memory.certainty,
                    visibility = memory.visibility,
                    evidenceRefs = memory.evidenceIds,
                )
            },
        )
    }

}

internal class RecordingMemoryIndexer : MemoryIndexer {
    val indexedMemories = mutableListOf<CanonicalMemoryContext>()

    override fun index(context: CanonicalMemoryContext) {
        indexedMemories += context
    }
}

internal object FailingMemoryIndexer : MemoryIndexer {
    override fun index(context: CanonicalMemoryContext) = error("qdrant unavailable")
}

internal object EmptyMemoryIndexingSource : MemoryIndexingSource {
    override fun findByIds(memoryIds: Collection<Int>): List<CanonicalMemoryContext> = emptyList()
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

internal class FakeSourceRecordRepository(
    private val existingKeys: Set<String> = emptySet(),
) : SourceRecordRepository {
    private val records = mutableListOf<SourceRecord>()
    var saveCalls = 0

    override fun findExistingDeduplicationKeys(sourceType: String, keys: Set<String>): Set<String> =
        (existingKeys + records.map { it.deduplicationKey }).filterTo(mutableSetOf()) { it in keys }

    override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>): List<SourceRecord> {
        saveCalls += 1
        return records.mapIndexed { index, record ->
            SourceRecord(
                id = index + 101,
                deduplicationKey = record.deduplicationKey,
                content = record.content,
            ).also(this.records::add)
        }
    }

    override fun findBySource(source: SourceDescriptor): List<SourceRecord> = records
}

internal class RecordingReviewStore : TopicAnalysisReviewStore {
    var createCalls = 0

    override fun create(
        requestedBy: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): TopicAnalysisReview {
        createCalls += 1
        return TopicAnalysisReview("preview-1", requestedBy, source, proposals)
    }

    override fun find(reviewId: String): TopicAnalysisReview? = null
}

internal class RecordingTopicExtractor : TopicExtractor {
    var calls = 0
    var document: SourceDocument? = null

    override suspend fun analyze(document: SourceDocument): List<TopicProposal> {
        calls += 1
        this.document = document
        return emptyList()
    }
}

internal object UnusedTopicExtractor : TopicExtractor {
    override suspend fun analyze(document: SourceDocument): List<TopicProposal> =
        error("not used")
}

internal fun kakaoText(): String =
    """
    2026년 6월 15일 오전 6:43, 동훈 : 첫 메시지
    2026년 6월 15일 오전 6:44, 승민 : 둘째 메시지
    """.trimIndent()

internal fun topic(title: String, evidenceRef: Int) =
    TopicProposal(
        title = title,
        summary = "요약",
        categories = listOf("family"),
        memories = listOf(
            MemoryProposal(
                content = "claim",
                subject = "subject",
                memoryType = MemoryType.STATE,
                certainty = MemoryCertainty.OBSERVED,
                evidenceIds = listOf(evidenceRef),
            ),
        ),
    )

internal fun request(text: String): TopicAnalysisRequest =
    TopicAnalysisRequest(
        userId = TEST_USER.value,
        source = KakaoExportParser.parse("family-kakao.txt", text),
    )

internal val TEST_USER = UserId("dad")
internal val TEST_ACCESS_POLICY = HouseholdAccessPolicy { it == TEST_USER }
