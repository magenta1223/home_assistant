package com.homeassistant.adapter.outbound.embedding.topic

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.application.port.output.group.PendingTopic
import com.homeassistant.application.port.output.group.descriptionFingerprint
import com.homeassistant.application.port.output.topic.TopicCandidate
import com.homeassistant.application.port.output.topic.TopicCatalog
import kotlin.test.*

class TopicEmbeddingAdapterTest {
    private val catalog = TopicCatalog { listOf(TopicCandidate(1, "가족 여행")) }

    @Test fun `uses passage convention and validates provider output`() {
        val inputs = mutableListOf<String>()
        val adapter = TopicEmbeddingAdapter(object : TextEmbedder {
            override fun embed(text: String): List<Float> { inputs.add(text); return listOf(3f, 4f) }
        }, "test-model", catalog)
        val result = adapter.generate(PendingTopic(1, descriptionFingerprint("가족 여행")))
        assertEquals(listOf("passage: 가족 여행"), inputs)
        assertEquals(listOf(0.6, 0.8), result.values())
        assertEquals("test-model", adapter.space.model)
        val invalid = TopicEmbeddingAdapter(object : TextEmbedder {
            override fun embed(text: String) = listOf(Float.NaN)
        }, "test-model", catalog)
        assertFailsWith<IllegalArgumentException> { invalid.generate(PendingTopic(1, descriptionFingerprint("가족 여행"))) }
    }

    @Test fun `an outdated version or a missing topic is not embedded`() {
        val adapter = TopicEmbeddingAdapter(object : TextEmbedder {
            override fun embed(text: String): List<Float> = error("must not embed")
        }, "test-model", catalog)
        assertFailsWith<IllegalArgumentException> { adapter.generate(PendingTopic(1, descriptionFingerprint("이전 설명"))) }
        assertFailsWith<IllegalArgumentException> { adapter.generate(PendingTopic(2, descriptionFingerprint("가족 여행"))) }
    }
}
