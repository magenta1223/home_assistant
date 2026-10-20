package com.homeassistant.domain.topic

import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TopicDescriptionTest {
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val onlyA = MemoryAccess.restricted(listOf(UserId("a")))
    private val onlyB = MemoryAccess.restricted(listOf(UserId("b")))

    @Test
    fun `a view holds PUBLIC and the viewer's slices only`() {
        val topic = topic(MemoryAccess.PUBLIC to listOf(1, 2), onlyA to listOf(3), onlyB to listOf(4))

        val a = topic.viewBy(UserId("a"))
        assertEquals(mapOf(MemoryAccess.PUBLIC to listOf(1, 2), onlyA to listOf(3)), a.slices)
        assertEquals(TopicViewKey(1, setOf(MemoryAccess.PUBLIC, onlyA)), a.key)
        assertEquals(mapOf(MemoryAccess.PUBLIC to 2, onlyA to 1), a.versions)
        assertEquals(TopicViewKey(1, setOf(MemoryAccess.PUBLIC)), topic.viewBy(UserId("c")).key)
        assertTrue(topic(onlyB to listOf(4)).viewBy(UserId("a")).isHidden)
    }

    @Test
    fun `the previous description stays until the view grew by the threshold`() {
        val policy = TopicDescriptionRefreshPolicy(growthThreshold = 0.2)
        val cached = TopicDescription("text", mapOf(MemoryAccess.PUBLIC to 5))

        assertEquals(TopicDescriptionRefresh.Generate, policy.decide(null, view(MemoryAccess.PUBLIC to 5)))
        assertEquals(TopicDescriptionRefresh.Reuse(cached), policy.decide(cached, view(MemoryAccess.PUBLIC to 5)))
        assertEquals(TopicDescriptionRefresh.Generate, policy.decide(cached, view(MemoryAccess.PUBLIC to 6)))
        val bigger = TopicDescription("text", mapOf(MemoryAccess.PUBLIC to 10))
        assertEquals(TopicDescriptionRefresh.Reuse(bigger), policy.decide(bigger, view(MemoryAccess.PUBLIC to 11)))
        assertEquals(TopicDescriptionRefresh.Generate, policy.decide(bigger, view(MemoryAccess.PUBLIC to 12)))
    }

    @Test
    fun `a view that lost memories regenerates even without growth`() {
        val cached = TopicDescription("text", mapOf(MemoryAccess.PUBLIC to 3, onlyA to 2))
        assertEquals(TopicDescriptionRefresh.Generate,
            TopicDescriptionRefreshPolicy().decide(cached, view(MemoryAccess.PUBLIC to 4, onlyA to 1)))
    }

    @Test
    fun `descriptions and thresholds reject empty values`() {
        assertFailsWith<IllegalArgumentException> { TopicDescription(" ", mapOf(MemoryAccess.PUBLIC to 1)) }
        assertFailsWith<IllegalArgumentException> { TopicDescription("text", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { TopicDescriptionRefreshPolicy(growthThreshold = 0.0) }
    }

    private fun topic(vararg slices: Pair<MemoryAccess, List<Int>>): Topic {
        val count = slices.sumOf { it.second.size }
        return Topic(1, (2..count).fold(TopicCentroid.of(east)) { sum, _ -> sum + east }, slices.toMap())
    }

    private fun view(vararg sizes: Pair<MemoryAccess, Int>): TopicView {
        var next = 1
        return TopicView(1, sizes.associate { (access, size) -> access to List(size) { next++ } })
    }
}
