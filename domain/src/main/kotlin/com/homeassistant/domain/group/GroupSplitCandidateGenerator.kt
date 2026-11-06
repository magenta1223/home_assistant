package com.homeassistant.domain.group

import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.domain.embedding.EmbeddingVector

class GroupSplitCandidateGenerator(private val settings: GroupPolicySettings) {
    fun generate(components: Map<Int, EmbeddingVector>): List<ComponentPartition> {
        require(components.isNotEmpty())
        val connected = connectedComponents(components).flatMap { bound(it, components) }
        val binary = bound(components.keys, components)
        return listOf(ComponentPartition(connected).canonical(), ComponentPartition(binary).canonical()).distinct()
    }

    private fun connectedComponents(components: Map<Int, EmbeddingVector>): List<Set<Int>> {
        val remaining = components.keys.toSortedSet()
        val parts = mutableListOf<Set<Int>>()
        while (remaining.isNotEmpty()) {
            val part = sortedSetOf(remaining.first())
            val queue = ArrayDeque<Int>()
            queue.add(remaining.first())
            remaining.remove(remaining.first())
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val neighbors = remaining.filter {
                    EmbeddingGeometry.cosine(components.getValue(current), components.getValue(it)) >= settings.splitThreshold
                }
                remaining.removeAll(neighbors.toSet())
                part.addAll(neighbors)
                queue.addAll(neighbors)
            }
            parts.add(part)
        }
        return parts
    }

    private fun bound(ids: Set<Int>, vectors: Map<Int, EmbeddingVector>): List<Set<Int>> {
        if (ids.size <= settings.maxComponents) return listOf(ids)
        val sorted = ids.sorted()
        val seeds = sorted.flatMapIndexed { index, a -> sorted.drop(index + 1).map { b -> a to b } }
            .minBy { (a, b) -> EmbeddingGeometry.cosine(vectors.getValue(a), vectors.getValue(b)) }
        val left = sortedSetOf(seeds.first)
        val right = sortedSetOf(seeds.second)
        sorted.filter { it != seeds.first && it != seeds.second }.forEach { id ->
            val a = EmbeddingGeometry.cosine(vectors.getValue(id), vectors.getValue(seeds.first))
            val b = EmbeddingGeometry.cosine(vectors.getValue(id), vectors.getValue(seeds.second))
            when {
                a > b -> left.add(id)
                b > a -> right.add(id)
                left.size <= right.size -> left.add(id)
                else -> right.add(id)
            }
        }
        return bound(left, vectors) + bound(right, vectors)
    }
}
