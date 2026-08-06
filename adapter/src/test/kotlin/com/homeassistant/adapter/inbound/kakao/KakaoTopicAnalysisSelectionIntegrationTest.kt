package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopics
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KakaoTopicAnalysisSelectionIntegrationTest {
    @Test
    fun `analyze stores and sends only new source records to llm`() = runBlocking {
        val text = kakaoText()
        val parsed = KakaoExportParser.parse("family-kakao.txt", text)
        val extractor = RecordingTopicExtractor()
        val previewStore = RecordingPreviewStore()
        val useCase = AnalyzeSource(
            topicExtractor = extractor,
            sourceTextParser = KakaoExportParser,
            sourceRecords = FakeSourceRecordStore(setOf(parsed.first().deduplicationKey)),
            previewRepository = previewStore,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = useCase.execute(request(text))

        assertEquals(1, result.importedRecordCount)
        assertEquals(1, extractor.calls)
        assertEquals(101, extractor.document!!.records.single().id)
        assertContains(extractor.document!!.records.single().content, "승민 | 2026년 6월 15일 오전 6:44 | 둘째 메시지")
        assertFalse(extractor.document!!.records.single().content.contains("첫 메시지"))
        assertEquals(1, previewStore.createCalls)
    }

    @Test
    fun `save selected analysis persists only selected preview topics`() = runBlocking {
        val topicStore = FakeTopicStore()
        val index = RecordingMemorySearchIndex()
        val service = service(
            topicStore,
            FakePreviewStore(listOf(topic("첫 후보", 1), topic("둘째 후보", 2), topic("셋째 후보", 3))),
            index,
        )

        val result = service.saveSelected(selection(setOf(2, 0, 99)))

        assertEquals(listOf("첫 후보", "셋째 후보"), result.topics.map { it.title })
        assertEquals(listOf("첫 후보", "셋째 후보"), topicStore.createdTopics.map { it.title })
        assertEquals(
            listOf("첫 후보", "셋째 후보"),
            index.indexedDocuments.map { it.topicTitle },
        )
    }

    @Test
    fun `save selected analysis with empty selection creates no topics`() = runBlocking {
        val result = service(
            FakeTopicStore(),
            FakePreviewStore(listOf(topic("첫 후보", 1))),
        ).saveSelected(selection(emptySet()))

        assertEquals(emptyList(), result.topics)
    }

    @Test
    fun `save selected analysis keeps topics pending when vector indexing fails`() = runBlocking {
        val outbox = FakeIndexingOutboxStore()
        val service = SaveAnalyzedTopics(
            topicRepository = FakeTopicStore(),
            previewRepository = FakePreviewStore(listOf(topic("후보", 1))),
            memorySearchIndex = FailingMemorySearchIndex,
            indexingOutbox = outbox,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.saveSelected(selection(setOf(0)))

        assertEquals(listOf("후보"), result.topics.map { it.title })
        assertEquals(listOf(1), outbox.pending(IndexTargetType.MEMORY))
    }

    private fun service(
        topicStore: FakeTopicStore,
        previewStore: FakePreviewStore,
        index: RecordingMemorySearchIndex = RecordingMemorySearchIndex(),
    ) = SaveAnalyzedTopics(
        topicRepository = topicStore,
        previewRepository = previewStore,
        memorySearchIndex = index,
        indexingOutbox = IndexingOutboxes.noOp(),
        accessPolicy = TEST_ACCESS_POLICY,
    )

    private fun selection(indices: Set<Int>) =
        TopicAnalysisSelectionSaveRequest(
            "preview-1",
            TEST_USER.value,
            indices,
        )
}
