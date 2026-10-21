package com.homeassistant.domain.retrieval

import com.homeassistant.domain.group.TopicVector
import kotlin.test.*

class BackboneSelectionTest {
    private fun vector(vararg values: Double) = TopicVector.normalized(values.toList())

    @Test fun `a near duplicate of a pick loses to a less relevant candidate elsewhere`() {
        val candidates = mapOf(
            1 to vector(1.0, 0.0),
            2 to vector(1.0, 0.02),
            3 to vector(0.95, 0.312),
        )

        val picked = BackboneSelection(neighbors = 1).select(candidates, vector(1.0, 0.0), limit = 2)

        assertEquals(listOf(1, 3), picked)
    }

    @Test fun `the most relevant candidate comes first and the limit caps the picks`() {
        val candidates = mapOf(1 to vector(0.0, 1.0), 2 to vector(1.0, 0.0), 3 to vector(1.0, 1.0))

        assertEquals(listOf(2), BackboneSelection().select(candidates, vector(1.0, 0.0), limit = 1))
        assertEquals(3, BackboneSelection().select(candidates, vector(1.0, 0.0), limit = 5).size)
    }

    @Test fun `equal candidates keep the smaller id and no candidate picks nothing`() {
        val same = vector(1.0, 0.0)

        assertEquals(listOf(4, 7), BackboneSelection().select(mapOf(7 to same, 4 to same), same, limit = 2))
        assertTrue(BackboneSelection().select(emptyMap(), same, limit = 3).isEmpty())
    }
}
