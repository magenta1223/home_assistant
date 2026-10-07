package com.homeassistant.domain.topic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TopicTest {
    @Test
    fun `a memory can belong to multiple categories and topics`() {
        val travel = Topic(1, "Travel", mapOf("Schedule" to listOf(10), "Booking" to listOf(10, 11)))
        val budget = Topic(2, "Budget", mapOf("Expenses" to listOf(10)))

        assertEquals(listOf(10), travel.memories.getValue("Schedule"))
        assertEquals(listOf(10, 11), travel.memories.getValue("Booking"))
        assertEquals(listOf(10), budget.memories.getValue("Expenses"))
    }

    @Test
    fun `empty topic is allowed while empty categories are rejected`() {
        assertEquals(emptyMap(), Topic(1, "Travel", emptyMap()).memories)
        assertFailsWith<IllegalArgumentException> { Topic(1, "Travel", mapOf("Schedule" to emptyList())) }
    }

    @Test
    fun `identifiers and labels must be meaningful`() {
        for (id in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { Topic(id, "Travel", emptyMap()) }
            assertFailsWith<IllegalArgumentException> { Topic(1, "Travel", mapOf("Schedule" to listOf(id))) }
        }
        assertFailsWith<IllegalArgumentException> { Topic(1, "  ", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Topic(1, "Travel", mapOf(" " to listOf(10))) }
    }

    @Test
    fun `duplicate memory references in one category are rejected`() {
        assertFailsWith<IllegalArgumentException> { Topic(1, "Travel", mapOf("Schedule" to listOf(10, 10))) }
    }
}
