package com.homeassistant.domain.topic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TopicRelationTest {
    @Test
    fun `both directions identify the same relation`() {
        val forward = TopicRelation.between(1, 2)
        val reverse = TopicRelation.between(2, 1)

        assertEquals(forward, reverse)
        assertEquals(1, forward.firstTopicId)
        assertEquals(2, forward.secondTopicId)
        assertEquals(1, setOf(forward, reverse).size)
    }

    @Test
    fun `self relations and invalid endpoints are rejected`() {
        assertFailsWith<IllegalArgumentException> { TopicRelation.between(1, 1) }
        assertFailsWith<IllegalArgumentException> { TopicRelation.between(0, 1) }
        assertFailsWith<IllegalArgumentException> { TopicRelation.between(1, -1) }
    }

    @Test
    fun `direct construction and copying cannot bypass canonical endpoints`() {
        assertFailsWith<IllegalArgumentException> { TopicRelation(2, 1) }
        assertFailsWith<IllegalArgumentException> { TopicRelation(1, 1) }
        assertFailsWith<IllegalArgumentException> { TopicRelation(0, 1) }
        val relation = TopicRelation.between(1, 2)
        assertFailsWith<IllegalArgumentException> { relation.copy(firstTopicId = 2) }
        assertFailsWith<IllegalArgumentException> { relation.copy(secondTopicId = 0) }
        assertFailsWith<IllegalArgumentException> { relation.copy(firstTopicId = 3) }
    }

    @Test
    fun `relations allow a cycle without a parent or root`() {
        val cycle = setOf(TopicRelation.between(1, 2), TopicRelation.between(2, 3), TopicRelation.between(3, 1))

        assertEquals(3, cycle.size)
        assertEquals(setOf(1, 2, 3), cycle.flatMap { listOf(it.firstTopicId, it.secondTopicId) }.toSet())
    }
}
