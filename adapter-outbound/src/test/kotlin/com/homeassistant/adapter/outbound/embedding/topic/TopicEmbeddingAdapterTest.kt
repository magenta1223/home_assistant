package com.homeassistant.adapter.outbound.embedding.topic

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import kotlin.test.*

class TopicEmbeddingAdapterTest {
    @Test fun `uses passage convention and validates provider output`() {
        val inputs = mutableListOf<String>()
        val adapter = TopicEmbeddingAdapter(object : TextEmbedder {
            override fun embed(text: String): List<Float> { inputs.add(text); return listOf(3f, 4f) }
        }, "test-model")
        val result = adapter.generate("가족 여행")
        assertEquals(listOf("passage: 가족 여행"), inputs)
        assertEquals(listOf(0.6, 0.8), result.values())
        assertEquals("test-model", adapter.space.model)
        val invalid = TopicEmbeddingAdapter(object : TextEmbedder {
            override fun embed(text: String) = listOf(Float.NaN)
        }, "test-model")
        assertFailsWith<IllegalArgumentException> { invalid.generate("topic") }
    }
}
