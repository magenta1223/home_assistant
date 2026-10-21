package com.homeassistant.domain.topic

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TopicTest {
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val ab = MemoryAccess.restricted(listOf(UserId("a"), UserId("b")))

    @Test
    fun `memories are listed by their access and the centroid covers all of them`() {
        val topic = Topic(1, TopicCentroid.of(east) + north + east, mapOf(MemoryAccess.PUBLIC to listOf(10, 12), ab to listOf(11)))

        assertEquals(listOf(10, 12), topic.slices.getValue(MemoryAccess.PUBLIC))
        assertEquals(listOf(11), topic.slices.getValue(ab))
        assertEquals(3, topic.centroid.count)
    }

    @Test
    fun `slices must be non-empty, disjoint and match the centroid count`() {
        val two = TopicCentroid.of(east) + north
        assertFailsWith<IllegalArgumentException> { Topic(0, two, mapOf(MemoryAccess.PUBLIC to listOf(10, 11))) }
        assertFailsWith<IllegalArgumentException> { Topic(1, two, mapOf(MemoryAccess.PUBLIC to listOf(10, 11), ab to emptyList())) }
        assertFailsWith<IllegalArgumentException> { Topic(1, two, mapOf(MemoryAccess.PUBLIC to listOf(10), ab to listOf(10))) }
        assertFailsWith<IllegalArgumentException> { Topic(1, two, mapOf(MemoryAccess.PUBLIC to listOf(10))) }
        assertFailsWith<IllegalArgumentException> { Topic(1, two, mapOf(MemoryAccess.PUBLIC to listOf(10, -1))) }
    }

    @Test
    fun `adding a vector keeps the direction equal to the normalized mean`() {
        val vectors = listOf(east, north, TopicVector.normalized(listOf(1.0, 1.0)))
        val centroid = vectors.drop(1).fold(TopicCentroid.of(vectors.first())) { sum, vector -> sum + vector }

        assertEquals(3, centroid.count)
        assertEquals(1.0, GroupGeometry.cosine(GroupGeometry.centroid(vectors), centroid.direction), 1e-12)
        assertFailsWith<IllegalArgumentException> { centroid + TopicVector.normalized(listOf(1.0, 0.0, 0.0)) }
    }

    @Test
    fun `a memory starts a topic when none reaches the join threshold`() {
        val policy = TopicPlacementPolicy()

        assertEquals(TopicPlacement.Create, policy.decide(east, emptyMap()))
        assertEquals(TopicPlacement.Create, policy.decide(east, mapOf(1 to north, 2 to at(0.59))))
    }

    @Test
    fun `nearest topics above the join threshold fill up to three`() {
        val policy = TopicPlacementPolicy()

        assertEquals(TopicPlacement.Join(listOf(2)), policy.decide(east, mapOf(1 to north, 2 to at(0.65))))
        assertEquals(TopicPlacement.Join(listOf(4, 2, 3)),
            policy.decide(east, mapOf(1 to at(0.61), 2 to at(0.7), 3 to at(0.65), 4 to at(0.95))))
    }

    @Test
    fun `every topic at the join-all threshold is joined beyond three`() {
        val policy = TopicPlacementPolicy()
        val centroids = mapOf(1 to at(0.85), 2 to at(0.99), 3 to at(0.9), 4 to at(0.81), 5 to at(0.7))

        assertEquals(TopicPlacement.Join(listOf(2, 3, 1, 4)), policy.decide(east, centroids))
    }

    @Test
    fun `equal cosine goes to the smaller topic id first`() {
        assertEquals(TopicPlacement.Join(listOf(3, 4)), TopicPlacementPolicy().decide(east, mapOf(4 to east, 3 to east)))
    }

    /** A unit vector whose cosine to [east] is [cosine]. */
    private fun at(cosine: Double) = TopicVector.normalized(listOf(cosine, sqrt(1 - cosine * cosine)))
}
