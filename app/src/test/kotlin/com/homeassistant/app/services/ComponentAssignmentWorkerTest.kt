package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.memoryextraction.MemoryHierarchyUpdate
import com.homeassistant.application.port.inbound.memoryextraction.UpdateMemoryHierarchy
import kotlin.test.Test
import kotlin.test.assertEquals

class ComponentAssignmentWorkerTest {
    @Test
    fun `a round updates the hierarchy with the batch size`() {
        val batches = mutableListOf<Int>()
        ComponentAssignmentWorker(UpdateMemoryHierarchy { batches += it; MemoryHierarchyUpdate.Skipped }, batchSize = 7)
            .runRound()
        assertEquals(listOf(7), batches)
    }

    @Test
    fun `a failing round is contained`() {
        ComponentAssignmentWorker(UpdateMemoryHierarchy { error("store unavailable") }).runRound()
    }
}
