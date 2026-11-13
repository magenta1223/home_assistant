package com.homeassistant.domain.group

data class Group(val id: Int, val componentIds: Set<Int>) {
    init {
        require(id > 0)
        require(componentIds.isNotEmpty() && componentIds.all { it > 0 })
    }
}

/** A complete, disjoint replacement of the supplied components, independent of persistent group IDs. */
data class ComponentPartition(val parts: List<Set<Int>>) {
    init {
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() })
        require(parts.flatten().all { it > 0 })
        require(parts.sumOf { it.size } == parts.flatten().toSet().size) { "duplicate component in partition" }
    }

    fun requireConserves(componentIds: Set<Int>) {
        require(parts.flatten().toSet() == componentIds) { "partition must preserve exactly the input components" }
    }

    fun canonical(): ComponentPartition = ComponentPartition(parts.map { it.toSortedSet() }.sortedBy { it.first() })
}

data class GroupPolicySettings(
    val attachThreshold: Double = 0.24,
    val maxComponents: Int = 12,
    val splitThreshold: Double = 0.26,
    val mergeThreshold: Double = 0.26,
    /** How many nearest Groups a single-Component Group is offered to for merging (xMemory kNN size). */
    val mergeNeighbors: Int = 10,
) {
    init {
        require(attachThreshold.isFinite() && attachThreshold in -1.0..1.0)
        require(splitThreshold.isFinite() && splitThreshold in -1.0..1.0)
        require(mergeThreshold.isFinite() && mergeThreshold in -1.0..1.0)
        require(maxComponents >= 1)
        require(mergeNeighbors >= 1)
    }
}
