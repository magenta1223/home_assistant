package com.homeassistant.domain.topic

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
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
    fun `a memory joins the nearest topic at or above the threshold`() {
        val policy = TopicPlacementPolicy(joinThreshold = 0.6)
        val northEast = TopicVector.normalized(listOf(1.0, 1.0))

        assertEquals(TopicPlacement.Create, policy.decide(east, emptyMap()))
        assertEquals(TopicPlacement.Join(2), policy.decide(east, mapOf(1 to north, 2 to northEast)))
        assertEquals(TopicPlacement.Create, policy.decide(east, mapOf(1 to north)))
        assertEquals(TopicPlacement.Join(1), TopicPlacementPolicy(joinThreshold = 0.0).decide(east, mapOf(1 to north)))
        assertEquals(TopicPlacement.Join(3), policy.decide(east, mapOf(4 to east, 3 to east)))
    }
}
