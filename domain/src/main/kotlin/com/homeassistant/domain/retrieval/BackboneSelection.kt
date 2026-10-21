package com.homeassistant.domain.retrieval

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector

/**
 * xMemory Stage I (equation 4): greedily picks the candidate that adds the most uncovered neighbor weight plus query
 * relevance, so the picks spread over the candidates instead of crowding near one spot.
 *
 * A node's neighbors are its [neighbors] nearest other nodes of the whole level, weighted by their cosine (negatives
 * count as 0). Picking a candidate covers itself (weight 1) and its neighbors that are candidates too; the coverage gain
 * is the weight of what it newly covers divided by the total weight every candidate would cover alone. Relevance is the
 * query cosine mapped from -1..1 to 0..1. Neighbors are computed at each call, not stored.
 */
class BackboneSelection(private val neighbors: Int = 10) {
    init { require(neighbors >= 1) }

    /** The ids of the [neighbors] nodes nearest to [id] among [vectors], nearest first. */
    fun neighborsOf(id: Int, vectors: Map<Int, TopicVector>): List<Int> =
        nearestOthers(id, vectors).map { it.first }

    /**
     * Up to [limit] of [candidates] in pick order; an equal gain keeps the smaller id.
     * [vectors] hold every node of the level, so a candidate's neighbors are found across the whole level.
     */
    fun select(candidates: Set<Int>, vectors: Map<Int, TopicVector>, query: TopicVector, limit: Int): List<Int> {
        require(limit > 0)
        val reach = candidates.associateWith { coverage(it, candidates, vectors) }
        val total = reach.values.sumOf { it.values.sum() }
        val relevance = candidates.associateWith { (GroupGeometry.cosine(query, vectors.getValue(it)) + 1.0) / 2.0 }
        val picked = mutableListOf<Int>()
        val covered = mutableSetOf<Int>()
        repeat(minOf(limit, candidates.size)) {
            val next = candidates.filter { it !in picked }.sorted().maxBy { id ->
                reach.getValue(id).filterKeys { it !in covered }.values.sum() / total + relevance.getValue(id)
            }
            picked += next
            covered += reach.getValue(next).keys
        }
        return picked
    }

    /** What picking [id] covers among [candidates], with each covered node's weight. */
    private fun coverage(id: Int, candidates: Set<Int>, vectors: Map<Int, TopicVector>): Map<Int, Double> =
        nearestOthers(id, vectors).filter { it.first in candidates }
            .associate { (other, cosine) -> other to cosine.coerceAtLeast(0.0) } + (id to 1.0)

    private fun nearestOthers(id: Int, vectors: Map<Int, TopicVector>): List<Pair<Int, Double>> =
        GroupGeometry.nearest(vectors.getValue(id), vectors - id, neighbors)
}
