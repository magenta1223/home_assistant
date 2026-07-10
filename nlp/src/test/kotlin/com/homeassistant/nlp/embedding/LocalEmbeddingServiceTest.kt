package com.homeassistant.nlp.embedding

import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalEmbeddingServiceTest {
    @Test
    fun `embedding rejects blank text`() {
        val service = LocalEmbeddingService(FakeLocalTextEmbeddingPredictor(FloatArray(384) { 1f }))

        assertFailsWith<IllegalArgumentException> {
            service.embed("   ")
        }
    }

    @Test
    fun `embedding returns normalized 384 dimension vector`() {
        val service = LocalEmbeddingService(FakeLocalTextEmbeddingPredictor(FloatArray(384) { 1f }))

        val vector = service.embed("가족 일정")

        assertEquals(384, vector.size)
        val norm = sqrt(vector.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, absoluteTolerance = 0.0001)
    }

    @Test
    fun `embedding rejects unexpected vector size`() {
        val service = LocalEmbeddingService(FakeLocalTextEmbeddingPredictor(floatArrayOf(1f, 2f, 3f)))

        assertFailsWith<IllegalStateException> {
            service.embed("가족 일정")
        }
    }

    @Test
    fun `fromModelPath requires local tokenizer json`() {
        val modelPath = Files.createTempDirectory("missing-tokenizer")

        assertFailsWith<IllegalArgumentException> {
            LocalEmbeddingService.fromModelPath(modelPath)
        }
    }
}

private class FakeLocalTextEmbeddingPredictor(
    private val vector: FloatArray,
) : LocalTextEmbeddingPredictor {
    override fun predict(text: String): FloatArray = vector
    override fun close() = Unit
}
