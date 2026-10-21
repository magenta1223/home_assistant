package com.homeassistant.domain.group

import kotlin.math.sqrt

/** Finite, nonzero unit vector. Copying input prevents provider-owned buffers from changing decisions. */
class TopicVector private constructor(private val coordinates: List<Double>) {
    val dimension: Int get() = coordinates.size
    fun values(): List<Double> = coordinates.toList()

    override fun equals(other: Any?): Boolean = other is TopicVector && coordinates == other.coordinates
    override fun hashCode(): Int = coordinates.hashCode()

    companion object {
        fun normalized(values: List<Double>): TopicVector {
            require(values.isNotEmpty() && values.all { it.isFinite() }) { "invalid vector coordinates" }
            val scale = values.maxOf { kotlin.math.abs(it) }
            require(scale > 0.0) { "zero vector" }
            val scaled = values.map { it / scale }
            val length = sqrt(scaled.sumOf { it * it })
            return TopicVector(scaled.map { it / length })
        }

        /** Restores persisted normalized coordinates without accumulating normalization rounding errors. */
        fun restore(values: List<Double>): TopicVector {
            require(values.isNotEmpty() && values.all { it.isFinite() })
            require(kotlin.math.abs(values.sumOf { it * it } - 1.0) < 1e-9) { "stored vector is not normalized" }
            return TopicVector(values.toList())
        }
    }
}

object GroupGeometry {
    fun cosine(first: TopicVector, second: TopicVector): Double {
        require(first.dimension == second.dimension) { "vector dimension mismatch" }
        return first.values().zip(second.values()).sumOf { (a, b) -> a * b }.coerceIn(-1.0, 1.0)
    }

    /** The [k] ids whose vectors are nearest to [center], nearest first with their cosine; an equal cosine keeps the smaller id. */
    fun nearest(center: TopicVector, vectors: Map<Int, TopicVector>, k: Int): List<Pair<Int, Double>> =
        vectors.map { (id, vector) -> id to cosine(center, vector) }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .take(k)

    /** Only the direction of the equal-weight mean is needed for cosine comparisons. */
    fun centroid(vectors: Collection<TopicVector>): TopicVector {
        require(vectors.isNotEmpty())
        val dimension = vectors.first().dimension
        require(vectors.all { it.dimension == dimension }) { "vector dimension mismatch" }
        val rows = vectors.map { it.values() }
        return TopicVector.normalized(List(dimension) { axis -> rows.sumOf { it[axis] / rows.size } })
    }
}
