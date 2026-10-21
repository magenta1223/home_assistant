package com.homeassistant.domain.retrieval

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector

/**
 * xMemory Stage I (equation 4): greedily picks the candidate that adds the most uncovered neighbor weight plus query
 * relevance, so the picks spread over the candidates instead of crowding near one spot.
 *
 * Each candidate links to its [neighbors] nearest other candidates, weighted by their cosine (negatives count as 0).
 * Picking a candidate covers itself (weight 1) and those neighbors; the coverage gain is the weight of what it newly covers
 * divided by the total weight every candidate would cover alone. Relevance is the query cosine mapped from -1..1 to 0..1.
 * The graph is built from the candidates at each call, not stored.
 */
class BackboneSelection(private val neighbors: Int = 10) {
    init { require(neighbors >= 1) }

    /** Up to [limit] candidate ids in pick order; an equal gain keeps the smaller id. */
    fun select(candidates: Map<Int, TopicVector>, query: TopicVector, limit: Int): List<Int> {
        require(limit > 0)
        if (candidates.isEmpty()) return emptyList()
        val reach = coverageReach(candidates)
        val total = reach.values.sumOf { it.values.sum() }
        val relevance = candidates.mapValues { (GroupGeometry.cosine(query, it.value) + 1.0) / 2.0 }
        val picked = mutableListOf<Int>()
        val covered = mutableSetOf<Int>()
        repeat(minOf(limit, candidates.size)) {
            val next = candidates.keys.filter { it !in picked }.sorted().maxBy { id ->
                reach.getValue(id).filterKeys { it !in covered }.values.sum() / total + relevance.getValue(id)
            }
            picked += next
            covered += reach.getValue(next).keys
        }
        return picked
    }

    /** What picking each candidate covers, with each covered candidate's weight. */
    private fun coverageReach(candidates: Map<Int, TopicVector>): Map<Int, Map<Int, Double>> =
        candidates.mapValues { (id, vector) ->
            candidates.filterKeys { it != id }.map { (other, otherVector) -> other to GroupGeometry.cosine(vector, otherVector) }
                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
                .take(neighbors)
                .associate { (other, cosine) -> other to cosine.coerceAtLeast(0.0) } + (id to 1.0)
        }
}
