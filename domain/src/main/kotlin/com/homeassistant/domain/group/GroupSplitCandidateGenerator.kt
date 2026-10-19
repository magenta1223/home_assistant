package com.homeassistant.domain.group

class GroupSplitCandidateGenerator(private val settings: GroupPolicySettings) {
    fun generate(topics: Map<Int, TopicVector>): List<TopicPartition> {
        require(topics.isNotEmpty())
        val connected = connectedComponents(topics).flatMap { bound(it, topics) }
        val binary = bound(topics.keys, topics)
        return listOf(TopicPartition(connected).canonical(), TopicPartition(binary).canonical()).distinct()
    }

    private fun connectedComponents(topics: Map<Int, TopicVector>): List<Set<Int>> {
        val remaining = topics.keys.toSortedSet()
        val components = mutableListOf<Set<Int>>()
        while (remaining.isNotEmpty()) {
            val component = sortedSetOf(remaining.first())
            val queue = ArrayDeque<Int>()
            queue.add(remaining.first())
            remaining.remove(remaining.first())
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val neighbors = remaining.filter {
                    GroupGeometry.cosine(topics.getValue(current), topics.getValue(it)) >= settings.splitThreshold
                }
                remaining.removeAll(neighbors.toSet())
                component.addAll(neighbors)
                queue.addAll(neighbors)
            }
            components.add(component)
        }
        return components
    }

    private fun bound(ids: Set<Int>, vectors: Map<Int, TopicVector>): List<Set<Int>> {
        if (ids.size <= settings.maxTopics) return listOf(ids)
        val sorted = ids.sorted()
        val seeds = sorted.flatMapIndexed { index, a -> sorted.drop(index + 1).map { b -> a to b } }
            .minBy { (a, b) -> GroupGeometry.cosine(vectors.getValue(a), vectors.getValue(b)) }
        val left = sortedSetOf(seeds.first)
        val right = sortedSetOf(seeds.second)
        sorted.filter { it != seeds.first && it != seeds.second }.forEach { id ->
            val a = GroupGeometry.cosine(vectors.getValue(id), vectors.getValue(seeds.first))
            val b = GroupGeometry.cosine(vectors.getValue(id), vectors.getValue(seeds.second))
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
