package com.homeassistant.domain.group

sealed interface GroupSplit {
    data object Unchanged : GroupSplit
    data class Divided(
        val partition: TopicPartition,
        val before: OrganizationScore,
        val after: OrganizationScore,
    ) : GroupSplit {
        val forced: Boolean get() = after.total <= before.total
    }
}

class GroupSplitPolicy(
    private val settings: GroupPolicySettings,
    private val candidates: GroupSplitCandidateGenerator = GroupSplitCandidateGenerator(settings),
    private val scorer: GroupOrganizationScorer = GroupOrganizationScorer(),
) {
    fun decide(target: Set<Int>, surrounding: List<Set<Int>>, vectors: Map<Int, TopicVector>): GroupSplit {
        if (target.size <= settings.maxTopics) return GroupSplit.Unchanged
        val before = scorer.score(surrounding + listOf(target), vectors)
        val ranked = candidates.generate(vectors.filterKeys { it in target }).mapNotNull { partition ->
            partition.requireConserves(target)
            try {
                GroupSplit.Divided(partition, before, scorer.score(surrounding + partition.parts, vectors))
            } catch (_: IllegalArgumentException) {
                // A candidate whose mean is zero has no valid cosine geometry.
                null
            }
        }.sortedWith(compareByDescending<GroupSplit.Divided> { it.after.total }
            .thenBy { it.partition.parts.size }.thenComparator { a, b -> comparePartitions(a.partition, b.partition) })
        return requireNotNull(ranked.firstOrNull()) { "no valid split candidate" }
    }

    private fun comparePartitions(first: TopicPartition, second: TopicPartition): Int {
        first.parts.zip(second.parts).forEach { (a, b) ->
            a.sorted().zip(b.sorted()).forEach { (x, y) -> if (x != y) return x.compareTo(y) }
            if (a.size != b.size) return a.size.compareTo(b.size)
        }
        return first.parts.size.compareTo(second.parts.size)
    }
}
