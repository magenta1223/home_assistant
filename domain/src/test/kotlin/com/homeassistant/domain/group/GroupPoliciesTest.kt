package com.homeassistant.domain.group

import kotlin.test.*

class GroupPoliciesTest {
    private val x = vector(1.0, 0.0)
    private val y = vector(0.0, 1.0)

    @Test fun `placement is strict at threshold and breaks ties by group id`() {
        val policy = GroupPlacementPolicy(GroupPolicySettings(attachThreshold = 1.0))
        assertEquals(GroupPlacement.Create, policy.decide(x, mapOf(1 to x)))
        assertEquals(GroupPlacement.Create, policy.decide(x, emptyMap()))
        assertEquals(GroupPlacement.Attach(2), GroupPlacementPolicy(GroupPolicySettings()).decide(x, linkedMapOf(9 to x, 2 to x)))
    }

    @Test fun `sparsity measures balance rather than group count`() {
        val vectors = (1..12).associateWith { x }
        val scorer = GroupOrganizationScorer()
        fun score(vararg parts: Set<Int>) = scorer.score(parts.toList(), vectors)
        assertEquals(1.0, score((1..12).toSet()).sparsity)
        assertEquals(1.0, score((1..6).toSet(), (7..12).toSet()).sparsity)
        assertEquals(1.0, scorer.score((1..12).map { setOf(it) }, vectors).sparsity)
        assertEquals(0.8, score((1..9).toSet(), (10..12).toSet()).sparsity, 1e-12)
        assertEquals(144.0 / 244, score((1..11).toSet(), setOf(12)).sparsity, 1e-12)
    }

    @Test fun `semantic score matches cohesion and handles single group zero MAD and empty organization`() {
        val scorer = GroupOrganizationScorer()
        assertEquals(OrganizationScore(0.0, 0.0), scorer.score(emptyList(), emptyMap()))
        assertEquals(kotlin.math.sqrt(0.5), scorer.score(listOf(setOf(1, 2)), mapOf(1 to x, 2 to y)).semantics, 1e-12)
        assertEquals(1.0, scorer.score(listOf(setOf(1), setOf(2)), mapOf(1 to x, 2 to x)).semantics)
        assertEquals(1.0, scorer.score(listOf(setOf(1), setOf(2)), mapOf(1 to x, 2 to y)).semantics)
        assertEquals(2.0 / 3, scorer.score(listOf(setOf(1), setOf(2), setOf(3)), mapOf(1 to x, 2 to x, 3 to y)).semantics, 1e-12)
    }

    @Test fun `graph candidates preserve separated semantic clusters`() {
        val topics = mapOf(1 to x, 2 to x, 3 to y, 4 to y)
        val candidates = GroupSplitCandidateGenerator(GroupPolicySettings(maxTopics = 2)).generate(topics)
        assertTrue(TopicPartition(listOf(setOf(1, 2), setOf(3, 4))) in candidates)
        val split = assertIs<GroupSplit.Divided>(GroupSplitPolicy(GroupPolicySettings(maxTopics = 2)).decide(topics.keys, emptyList(), topics))
        assertEquals(listOf(setOf(1, 2), setOf(3, 4)), split.partition.parts)
        assertTrue(split.after.total > split.before.total)
    }

    @Test fun `identical vectors terminate deterministically and enforce cap without score gain`() {
        val topics = (1..13).associateWith { x }
        val policy = GroupSplitPolicy(GroupPolicySettings(maxTopics = 12))
        val split = assertIs<GroupSplit.Divided>(policy.decide(topics.keys, emptyList(), topics))
        split.partition.requireConserves(topics.keys)
        assertTrue(split.partition.parts.all { it.size <= 12 })
        assertEquals(listOf(7, 6), split.partition.parts.map { it.size })
        assertTrue(split.forced)
        assertEquals(split, policy.decide(topics.keys.reversed().toSet(), emptyList(), topics.toList().reversed().toMap()))
    }

    @Test fun `recursive fallback conserves membership down to singleton cap`() {
        val topics = (1..40).associateWith { x }
        val candidates = GroupSplitCandidateGenerator(GroupPolicySettings(maxTopics = 1)).generate(topics)
        candidates.forEach { partition ->
            partition.requireConserves(topics.keys)
            assertTrue(partition.parts.all { it.size == 1 })
        }
    }

    @Test fun `under cap is never split for score alone`() {
        assertEquals(GroupSplit.Unchanged, GroupSplitPolicy(GroupPolicySettings(maxTopics = 2))
            .decide(setOf(1, 2), emptyList(), mapOf(1 to x, 2 to y)))
    }

    @Test fun `invalid vectors dimensions and partitions fail explicitly`() {
        listOf(emptyList(), listOf(0.0, 0.0), listOf(Double.NaN), listOf(Double.POSITIVE_INFINITY)).forEach {
            assertFailsWith<IllegalArgumentException> { TopicVector.normalized(it) }
        }
        assertFailsWith<IllegalArgumentException> { GroupGeometry.cosine(x, vector(1.0)) }
        assertFailsWith<IllegalArgumentException> { GroupGeometry.centroid(listOf(x, vector(-1.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { TopicPartition(listOf(setOf(1), setOf(1))) }
        assertFailsWith<IllegalArgumentException> { TopicPartition(listOf(emptySet())) }
        assertFailsWith<IllegalArgumentException> { TopicPartition(listOf(setOf(1))).requireConserves(setOf(1, 2)) }
        assertFailsWith<IllegalArgumentException> { GroupPolicySettings(maxTopics = 0) }
    }

    @Test fun `normalization supports large finite inputs and protects input from mutation`() {
        val original = mutableListOf(Double.MAX_VALUE, Double.MAX_VALUE)
        val vector = TopicVector.normalized(original)
        original[0] = 0.0
        assertEquals(kotlin.math.sqrt(0.5), vector.values().first(), 1e-12)
        assertEquals(vector, TopicVector.restore(vector.values()))
    }

    private fun vector(vararg values: Double) = TopicVector.normalized(values.toList())
}
