package com.homeassistant.domain.component

import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentDescriptionTest {
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val onlyA = EpisodeAccess.restricted(listOf(UserId("a")))
    private val onlyB = EpisodeAccess.restricted(listOf(UserId("b")))

    @Test
    fun `a view holds PUBLIC and the viewer's slices only`() {
        val component = component(EpisodeAccess.PUBLIC to listOf(1, 2), onlyA to listOf(3), onlyB to listOf(4))

        val a = component.viewBy(UserId("a"))
        assertEquals(mapOf(EpisodeAccess.PUBLIC to listOf(1, 2), onlyA to listOf(3)), a.slices)
        assertEquals(ComponentViewKey(1, setOf(EpisodeAccess.PUBLIC, onlyA)), a.key)
        assertEquals(mapOf(EpisodeAccess.PUBLIC to 2, onlyA to 1), a.versions)
        assertEquals(ComponentViewKey(1, setOf(EpisodeAccess.PUBLIC)), component.viewBy(UserId("c")).key)
        assertTrue(component(onlyB to listOf(4)).viewBy(UserId("a")).isHidden)
    }

    @Test
    fun `the previous description stays until the view grew by the threshold`() {
        val policy = ComponentDescriptionRefreshPolicy(growthThreshold = 0.2)
        val cached = ComponentDescription("text", mapOf(EpisodeAccess.PUBLIC to 5))

        assertEquals(ComponentDescriptionRefresh.Generate, policy.decide(null, view(EpisodeAccess.PUBLIC to 5)))
        assertEquals(ComponentDescriptionRefresh.Reuse(cached), policy.decide(cached, view(EpisodeAccess.PUBLIC to 5)))
        assertEquals(ComponentDescriptionRefresh.Generate, policy.decide(cached, view(EpisodeAccess.PUBLIC to 6)))
        val bigger = ComponentDescription("text", mapOf(EpisodeAccess.PUBLIC to 10))
        assertEquals(ComponentDescriptionRefresh.Reuse(bigger), policy.decide(bigger, view(EpisodeAccess.PUBLIC to 11)))
        assertEquals(ComponentDescriptionRefresh.Generate, policy.decide(bigger, view(EpisodeAccess.PUBLIC to 12)))
    }

    @Test
    fun `a view that lost episodes regenerates even without growth`() {
        val cached = ComponentDescription("text", mapOf(EpisodeAccess.PUBLIC to 3, onlyA to 2))
        assertEquals(ComponentDescriptionRefresh.Generate,
            ComponentDescriptionRefreshPolicy().decide(cached, view(EpisodeAccess.PUBLIC to 4, onlyA to 1)))
    }

    @Test
    fun `descriptions and thresholds reject empty values`() {
        assertFailsWith<IllegalArgumentException> { ComponentDescription(" ", mapOf(EpisodeAccess.PUBLIC to 1)) }
        assertFailsWith<IllegalArgumentException> { ComponentDescription("text", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ComponentDescriptionRefreshPolicy(growthThreshold = 0.0) }
    }

    private fun component(vararg slices: Pair<EpisodeAccess, List<Int>>): Component {
        val count = slices.sumOf { it.second.size }
        return Component(1, (2..count).fold(ComponentCentroid.of(east)) { sum, _ -> sum + east }, slices.toMap())
    }

    private fun view(vararg sizes: Pair<EpisodeAccess, Int>): ComponentView {
        var next = 1
        return ComponentView(1, sizes.associate { (access, size) -> access to List(size) { next++ } })
    }
}
