package com.homeassistant.domain.group

import kotlin.math.abs
import kotlin.math.exp

data class OrganizationScore(val sparsity: Double, val semantics: Double) {
    val total: Double get() = sparsity + semantics
}

/** xMemory equations 1-3; sparsity measures size balance, not a penalty on the group count. */
class GroupOrganizationScorer {
    fun score(parts: List<Set<Int>>, vectors: Map<Int, TopicVector>): OrganizationScore {
        if (parts.isEmpty()) return OrganizationScore(0.0, 0.0)
        TopicPartition(parts)
        val sizes = parts.map { it.size.toDouble() }
        val count = sizes.sum()
        val sparsity = count * count / (parts.size * sizes.sumOf { it * it })
        val centroids = parts.map { part -> GroupGeometry.centroid(part.sorted().map { vectors.getValue(it) }) }
        val cohesion = parts.mapIndexed { index, part ->
            part.sorted().map { GroupGeometry.cosine(vectors.getValue(it), centroids[index]) }.average()
        }
        if (parts.size == 1) return OrganizationScore(sparsity, cohesion.single())
        val nearest = centroids.indices.map { i ->
            centroids.indices.filter { it != i }.maxOf { j -> GroupGeometry.cosine(centroids[i], centroids[j]) }
        }
        val median = median(nearest)
        val sigma = median(nearest.map { abs(it - median) }) + 1e-9
        val semantic = nearest.indices.map { i ->
            val distance = (nearest[i] - median) / sigma
            cohesion[i] * exp(-distance * distance / 2.0)
        }.average()
        return OrganizationScore(sparsity, semantic)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
