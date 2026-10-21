package com.homeassistant.domain.group

import kotlin.test.*

class GroupMergePolicyTest {
    private val east = vector(1.0, 0.0)
    private val north = vector(0.0, 1.0)
    private val policy = GroupMergePolicy(GroupPolicySettings())

    @Test fun `a single-topic group joins a near group when equation 1 improves`() {
        val vectors = mapOf(1 to east, 2 to vector(1.0, 0.1), 3 to vector(1.0, 0.05), 4 to vector(1.0, 0.08), 5 to north, 6 to north, 7 to north)
        val groups = listOf(Group(1, setOf(1, 3, 4)), Group(2, setOf(2)), Group(3, setOf(5, 6, 7)))

        val merge = assertNotNull(policy.decide(groups, centroids(groups, vectors), vectors))

        assertEquals(1 to 2, merge.survivor to merge.absorbed)
        assertTrue(merge.after.total > merge.before.total)
    }

    @Test fun `the neighbor with the largest improvement wins over the nearest`() {
        val vectors = mapOf(
            1 to east, 2 to vector(1.0, 0.2),
            4 to vector(1.0, 1.0), 5 to vector(1.0, 1.0), 6 to vector(1.0, 0.8), 11 to vector(1.0, 0.9), 12 to vector(1.0, 1.0),
            7 to north, 8 to north, 9 to north, 10 to vector(1.0, 0.5),
        )
        val groups = listOf(Group(1, setOf(1, 2)), Group(2, setOf(4, 5, 6, 11, 12)), Group(3, setOf(7, 8, 9)), Group(4, setOf(10)))
        val centroids = centroids(groups, vectors)
        assertTrue(GroupGeometry.cosine(centroids.getValue(4), centroids.getValue(2)) >
            GroupGeometry.cosine(centroids.getValue(4), centroids.getValue(1)))

        assertEquals(1 to 4, assertNotNull(policy.decide(groups, centroids, vectors)).let { it.survivor to it.absorbed })
    }

    @Test fun `isolated, over-cap and multi-topic groups are not merged`() {
        val apart = mapOf(1 to east, 2 to north)
        val single = listOf(Group(1, setOf(1)), Group(2, setOf(2)))
        assertNull(policy.decide(single, centroids(single, apart), apart))

        val alike = (1..13).associateWith { east }
        val full = listOf(Group(1, (1..12).toSet()), Group(2, setOf(13)))
        assertNull(policy.decide(full, centroids(full, alike), alike))

        val noSingles = listOf(Group(1, setOf(1, 2)), Group(2, setOf(3, 4)))
        val vectors = (1..4).associateWith { east }
        assertNull(policy.decide(noSingles, centroids(noSingles, vectors), vectors))
    }

    @Test fun `only the nearest merge-neighbors groups are candidates`() {
        val vectors = (1..15).associateWith { east }
        val groups = listOf(Group(1, (1..12).toSet()), Group(2, setOf(13, 14)), Group(3, setOf(15)))
        val centroids = centroids(groups, vectors)

        assertNull(GroupMergePolicy(GroupPolicySettings(mergeNeighbors = 1)).decide(groups, centroids, vectors))
        assertEquals(2 to 3, assertNotNull(policy.decide(groups, centroids, vectors)).let { it.survivor to it.absorbed })
    }

    private fun centroids(groups: List<Group>, vectors: Map<Int, TopicVector>) =
        groups.associate { group -> group.id to GroupGeometry.centroid(group.topicIds.sorted().map(vectors::getValue)) }

    private fun vector(vararg values: Double) = TopicVector.normalized(values.toList())
}
