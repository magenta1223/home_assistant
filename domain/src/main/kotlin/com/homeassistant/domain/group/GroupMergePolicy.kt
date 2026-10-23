package com.homeassistant.domain.group

/** [absorbed]'s Topics join [survivor], the smaller id, which keeps its id. */
data class GroupMerge(val survivor: Int, val absorbed: Int, val before: OrganizationScore, val after: OrganizationScore) {
    init { require(survivor in 1 until absorbed) }
}

/**
 * xMemory merge: a Group holding a single Topic is offered to its [GroupPolicySettings.mergeNeighbors] nearest Groups
 * whose centroid cosine reaches [GroupPolicySettings.mergeThreshold] and that stay within [GroupPolicySettings.maxTopics]
 * together. Of those, the merge that raises equation 1 over all Groups the most is chosen. Single-Topic Groups are tried
 * in id order and one merge is returned at a time.
 */
class GroupMergePolicy(
    private val settings: GroupPolicySettings,
    private val scorer: GroupOrganizationScorer = GroupOrganizationScorer(),
) {
    fun decide(groups: List<Group>, centroids: Map<Int, TopicVector>, vectors: Map<Int, TopicVector>): GroupMerge? {
        val before by lazy { scorer.score(groups.map { it.topicIds }, vectors) }
        for (single in groups.filter { it.topicIds.size == 1 }.sortedBy { it.id }) {
            // Nearest first, so an equal score keeps the nearer Group.
            val best = neighbors(single, groups, centroids).map { target ->
                val merged = groups.filter { it.id != single.id && it.id != target.id }.map { it.topicIds } +
                    listOf(single.topicIds + target.topicIds)
                GroupMerge(minOf(single.id, target.id), maxOf(single.id, target.id), before, scorer.score(merged, vectors))
            }.maxByOrNull { it.after.total }
            if (best != null && best.after.total > before.total) return best
        }
        return null
    }

    private fun neighbors(single: Group, groups: List<Group>, centroids: Map<Int, TopicVector>): List<Group> {
        val others = groups.filter { it.id != single.id }.associateBy { it.id }
        return GroupGeometry.nearest(centroids.getValue(single.id), others.mapValues { centroids.getValue(it.key) }, settings.mergeNeighbors)
            .map { (id, cosine) -> others.getValue(id) to cosine }
            .filter { (group, cosine) -> cosine >= settings.mergeThreshold && group.topicIds.size + 1 <= settings.maxTopics }
            .map { it.first }
    }
}
