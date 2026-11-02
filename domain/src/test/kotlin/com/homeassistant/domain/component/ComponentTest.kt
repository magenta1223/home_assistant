package com.homeassistant.domain.component

import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComponentTest {
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val ab = EpisodeAccess.restricted(listOf(UserId("a"), UserId("b")))

    @Test
    fun `episodes are listed by their access and the centroid covers all of them`() {
        val component = Component(1, ComponentCentroid.of(east) + north + east, mapOf(EpisodeAccess.PUBLIC to listOf(10, 12), ab to listOf(11)))

        assertEquals(listOf(10, 12), component.slices.getValue(EpisodeAccess.PUBLIC))
        assertEquals(listOf(11), component.slices.getValue(ab))
        assertEquals(3, component.centroid.count)
    }

    @Test
    fun `slices must be non-empty, disjoint and match the centroid count`() {
        val two = ComponentCentroid.of(east) + north
        assertFailsWith<IllegalArgumentException> { Component(0, two, mapOf(EpisodeAccess.PUBLIC to listOf(10, 11))) }
        assertFailsWith<IllegalArgumentException> { Component(1, two, mapOf(EpisodeAccess.PUBLIC to listOf(10, 11), ab to emptyList())) }
        assertFailsWith<IllegalArgumentException> { Component(1, two, mapOf(EpisodeAccess.PUBLIC to listOf(10), ab to listOf(10))) }
        assertFailsWith<IllegalArgumentException> { Component(1, two, mapOf(EpisodeAccess.PUBLIC to listOf(10))) }
        assertFailsWith<IllegalArgumentException> { Component(1, two, mapOf(EpisodeAccess.PUBLIC to listOf(10, -1))) }
    }

    @Test
    fun `adding a vector keeps the direction equal to the normalized mean`() {
        val vectors = listOf(east, north, ComponentVector.normalized(listOf(1.0, 1.0)))
        val centroid = vectors.drop(1).fold(ComponentCentroid.of(vectors.first())) { sum, vector -> sum + vector }

        assertEquals(3, centroid.count)
        assertEquals(1.0, GroupGeometry.cosine(GroupGeometry.centroid(vectors), centroid.direction), 1e-12)
        assertFailsWith<IllegalArgumentException> { centroid + ComponentVector.normalized(listOf(1.0, 0.0, 0.0)) }
    }

    @Test
    fun `an episode starts a component when none reaches the join threshold`() {
        val policy = ComponentPlacementPolicy()

        assertEquals(ComponentPlacement.Create, policy.decide(east, emptyMap()))
        assertEquals(ComponentPlacement.Create, policy.decide(east, mapOf(1 to north, 2 to at(0.59))))
    }

    @Test
    fun `nearest components above the join threshold fill up to three`() {
        val policy = ComponentPlacementPolicy()

        assertEquals(ComponentPlacement.Join(listOf(2)), policy.decide(east, mapOf(1 to north, 2 to at(0.65))))
        assertEquals(ComponentPlacement.Join(listOf(4, 2, 3)),
            policy.decide(east, mapOf(1 to at(0.61), 2 to at(0.7), 3 to at(0.65), 4 to at(0.95))))
    }

    @Test
    fun `every component at the join-all threshold is joined beyond three`() {
        val policy = ComponentPlacementPolicy()
        val centroids = mapOf(1 to at(0.85), 2 to at(0.99), 3 to at(0.9), 4 to at(0.81), 5 to at(0.7))

        assertEquals(ComponentPlacement.Join(listOf(2, 3, 1, 4)), policy.decide(east, centroids))
    }

    @Test
    fun `equal cosine goes to the smaller component id first`() {
        assertEquals(ComponentPlacement.Join(listOf(3, 4)), ComponentPlacementPolicy().decide(east, mapOf(4 to east, 3 to east)))
    }

    /** A unit vector whose cosine to [east] is [cosine]. */
    private fun at(cosine: Double) = ComponentVector.normalized(listOf(cosine, sqrt(1 - cosine * cosine)))
}
