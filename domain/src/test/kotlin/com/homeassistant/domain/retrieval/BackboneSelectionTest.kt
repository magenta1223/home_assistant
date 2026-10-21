package com.homeassistant.domain.retrieval

import com.homeassistant.domain.group.TopicVector
import kotlin.test.*

class BackboneSelectionTest {
    private fun vector(vararg values: Double) = TopicVector.normalized(values.toList())

    @Test fun `a near duplicate of a pick loses to a less relevant candidate elsewhere`() {
        val vectors = mapOf(
            1 to vector(1.0, 0.0),
            2 to vector(1.0, 0.02),
            3 to vector(0.95, 0.312),
        )

        val picked = BackboneSelection(neighbors = 1).select(vectors.keys, vectors, vector(1.0, 0.0), limit = 2)

        assertEquals(listOf(1, 3), picked)
    }

    @Test fun `neighbors come from the whole level but only candidates count as covered`() {
        val vectors = mapOf(
            1 to vector(1.0, 0.0),
            2 to vector(1.0, 0.02),
            3 to vector(0.95, 0.312),
            9 to vector(1.0, 0.01),
        )
        val selection = BackboneSelection(neighbors = 1)

        assertEquals(listOf(9), selection.neighborsOf(1, vectors))
        // 1 and 2 lose their nearest neighbor 9 to the non-candidates, so 3, which still covers 2, gains the most.
        assertEquals(listOf(3, 1), selection.select(setOf(1, 2, 3), vectors, vector(1.0, 0.0), limit = 2))
    }

    @Test fun `the most relevant candidate comes first and the limit caps the picks`() {
        val vectors = mapOf(1 to vector(0.0, 1.0), 2 to vector(1.0, 0.0), 3 to vector(1.0, 1.0))

        assertEquals(listOf(2), BackboneSelection().select(vectors.keys, vectors, vector(1.0, 0.0), limit = 1))
        assertEquals(3, BackboneSelection().select(vectors.keys, vectors, vector(1.0, 0.0), limit = 5).size)
    }

    @Test fun `equal candidates keep the smaller id and no candidate picks nothing`() {
        val same = vector(1.0, 0.0)
        val vectors = mapOf(7 to same, 4 to same)

        assertEquals(listOf(4, 7), BackboneSelection().select(vectors.keys, vectors, same, limit = 2))
        assertTrue(BackboneSelection().select(emptySet(), vectors, same, limit = 3).isEmpty())
    }
}
