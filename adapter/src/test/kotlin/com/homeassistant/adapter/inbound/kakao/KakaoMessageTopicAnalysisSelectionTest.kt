package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.TopicAnalysisFactory
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
        val service = TopicAnalysisFactory.kakao(
            topicExtractor = extractor,
            sourceTextParser = KakaoExportParser,
            importer = KakaoImporterFactory.create(FakeKakaoMessageStore(setOf(parsed.first().fingerprint))),
            topicStore = FakeTopicStore(),
            previewStore = previewStore,
            indexingOutbox = IndexingOutboxes.noOp(),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.analyze(request(text))

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
        val index = RecordingTopicClaimSearchIndex()
        val service = service(
            kakaoStore,
            topicStore,
            FakePreviewStore(listOf(topic("첫 후보", 1), topic("둘째 후보", 2), topic("셋째 후보", 3))),
            index,
        )

        val result = service.saveSelectedAnalysis(selection(setOf(2, 0, 99)))

        assertEquals(listOf("첫 후보", "셋째 후보"), result.topics.map { it.title })
        assertEquals(listOf("첫 후보", "셋째 후보"), topicStore.createdTopics.map { it.title })
        assertEquals(listOf("첫 후보", "셋째 후보"), index.indexedTopics.map { it.title })
        assertEquals(1, kakaoStore.importCalls)
    }

    @Test
    fun `save selected analysis with empty selection does not import kakao messages`() = runBlocking {
        val kakaoStore = FakeKakaoMessageStore()
        val result = service(
            kakaoStore,
            FakeTopicStore(),
            FakePreviewStore(listOf(topic("첫 후보", 1))),
        ).saveSelectedAnalysis(selection(emptySet()))

        assertEquals(emptyList(), result.topics)
        assertEquals(0, kakaoStore.importCalls)
    }

    @Test
    fun `save selected analysis keeps topics pending when vector indexing fails`() = runBlocking {
        val outbox = FakeIndexingOutboxStore()
        val service = TopicAnalysisFactory.kakao(
            UnusedTopicExtractor,
            KakaoExportParser,
            KakaoImporterFactory.create(FakeKakaoMessageStore()),
            FakeTopicStore(),
            FakePreviewStore(listOf(topic("후보", 1))),
            FailingTopicClaimSearchIndex,
            outbox,
            TEST_ACCESS_POLICY,
        )

        val result = service.saveSelectedAnalysis(selection(setOf(0)))

        assertEquals(listOf("후보"), result.topics.map { it.title })
        assertEquals(listOf(1), outbox.pending(IndexTargetType.TOPIC))
    }

    private fun service(
        kakaoStore: FakeKakaoMessageStore,
        topicStore: FakeTopicStore,
        previewStore: FakePreviewStore,
        index: RecordingTopicClaimSearchIndex = RecordingTopicClaimSearchIndex(),
    ) = TopicAnalysisFactory.kakao(
        UnusedTopicExtractor,
        KakaoExportParser,
        KakaoImporterFactory.create(kakaoStore),
        topicStore,
        previewStore,
        index,
        IndexingOutboxes.noOp(),
        TEST_ACCESS_POLICY,
    )

    private fun selection(indices: Set<Int>) =
        TopicAnalysisSelectionSaveRequest(
            "preview-1",
            TEST_SCOPE.userId.value,
            TEST_SCOPE.familyId.value,
            indices,
        )
}
