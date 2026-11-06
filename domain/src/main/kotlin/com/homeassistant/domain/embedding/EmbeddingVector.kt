package com.homeassistant.domain.embedding

import kotlin.math.sqrt

/** Finite, nonzero unit vector. Copying input prevents provider-owned buffers from changing decisions. */
class EmbeddingVector private constructor(private val coordinates: List<Double>) {
    val dimension: Int get() = coordinates.size
    fun values(): List<Double> = coordinates.toList()

    override fun equals(other: Any?): Boolean = other is EmbeddingVector && coordinates == other.coordinates
    override fun hashCode(): Int = coordinates.hashCode()

    companion object {
        fun normalized(values: List<Double>): EmbeddingVector {
            require(values.isNotEmpty() && values.all { it.isFinite() }) { "invalid vector coordinates" }
            val scale = values.maxOf { kotlin.math.abs(it) }
            require(scale > 0.0) { "zero vector" }
            val scaled = values.map { it / scale }
            val length = sqrt(scaled.sumOf { it * it })
            return EmbeddingVector(scaled.map { it / length })
        }

        /** Restores persisted normalized coordinates without accumulating normalization rounding errors. */
        fun restore(values: List<Double>): EmbeddingVector {
            require(values.isNotEmpty() && values.all { it.isFinite() })
            require(kotlin.math.abs(values.sumOf { it * it } - 1.0) < 1e-9) { "stored vector is not normalized" }
            return EmbeddingVector(values.toList())
        }
    }
}
