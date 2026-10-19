package com.homeassistant.domain.group

data class Group(val id: Int, val topicIds: Set<Int>) {
    init {
        require(id > 0)
        require(topicIds.isNotEmpty() && topicIds.all { it > 0 })
    }
}

/** A complete, disjoint replacement of the supplied topics, independent of persistent group IDs. */
data class TopicPartition(val parts: List<Set<Int>>) {
    init {
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() })
        require(parts.flatten().all { it > 0 })
        require(parts.sumOf { it.size } == parts.flatten().toSet().size) { "duplicate topic in partition" }
    }

    fun requireConserves(topicIds: Set<Int>) {
        require(parts.flatten().toSet() == topicIds) { "partition must preserve exactly the input topics" }
    }

    fun canonical(): TopicPartition = TopicPartition(parts.map { it.toSortedSet() }.sortedBy { it.first() })
}

data class GroupPolicySettings(
    val attachThreshold: Double = 0.6,
    val maxTopics: Int = 12,
    val splitThreshold: Double = 0.65,
) {
    init {
        require(attachThreshold.isFinite() && attachThreshold in -1.0..1.0)
        require(splitThreshold.isFinite() && splitThreshold in -1.0..1.0)
        require(maxTopics >= 1)
    }
}
