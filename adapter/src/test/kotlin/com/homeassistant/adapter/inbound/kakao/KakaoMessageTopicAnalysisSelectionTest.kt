package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopics
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KakaoMessageTopicAnalysisSelectionTest {
    @Test
    fun `analyze sends only new messages to llm while preserving original evidence refs`() = runBlocking {
        val text = kakaoText()
        val parsed = KakaoExportParser.parse("family-kakao.txt", text)
        val extractor = RecordingTopicExtractor()
        val previewStore = RecordingPreviewStore()
        val useCase = AnalyzeSource(
            topicExtractor = extractor,
            sourceTextParser = KakaoExportParser,
            importService = KakaoImporterFactory.create(FakeKakaoMessageStore(setOf(parsed.first().fingerprint))),
            previewRepository = previewStore,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = useCase.execute(request(text))

        assertEquals(1, result.importedRecordCount)
        assertEquals(1, extractor.calls)
        assertContains(extractor.document!!.records.single().content, "승민 | 2026년 6월 15일 오전 6:44 | 둘째 메시지")
        assertFalse(extractor.document!!.records.single().content.contains("첫 메시지"))
        assertEquals(1, previewStore.createCalls)
    }

    @Test
    fun `save selected analysis persists only selected preview topics`() = runBlocking {
        val kakaoStore = FakeKakaoMessageStore()
        val topicStore = FakeTopicStore()
        val index = RecordingMemorySearchIndex()
        val service = service(
            kakaoStore,
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
        assertEquals(1, kakaoStore.importCalls)
    }

    @Test
    fun `save selected analysis with empty selection does not import kakao messages`() = runBlocking {
        val kakaoStore = FakeKakaoMessageStore()
        val result = service(
            kakaoStore,
            FakeTopicStore(),
            FakePreviewStore(listOf(topic("첫 후보", 1))),
        ).saveSelected(selection(emptySet()))

        assertEquals(emptyList(), result.topics)
        assertEquals(0, kakaoStore.importCalls)
    }

    @Test
    fun `save selected analysis keeps topics pending when vector indexing fails`() = runBlocking {
        val outbox = FakeIndexingOutboxStore()
        val service = SaveAnalyzedTopics(
            importService = KakaoImporterFactory.create(FakeKakaoMessageStore()),
            sourceTextParser = KakaoExportParser,
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
        kakaoStore: FakeKakaoMessageStore,
        topicStore: FakeTopicStore,
        previewStore: FakePreviewStore,
        index: RecordingMemorySearchIndex = RecordingMemorySearchIndex(),
    ) = SaveAnalyzedTopics(
        importService = KakaoImporterFactory.create(kakaoStore),
        sourceTextParser = KakaoExportParser,
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
