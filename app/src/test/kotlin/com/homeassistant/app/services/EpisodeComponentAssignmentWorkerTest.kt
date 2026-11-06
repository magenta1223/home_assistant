package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.memoryextraction.MemoryHierarchyUpdate
import com.homeassistant.application.port.inbound.memoryextraction.UpdateMemoryHierarchy
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeComponentAssignmentWorkerTest {
    @Test
    fun `a round updates the hierarchy with the batch size`() {
        val batches = mutableListOf<Int>()
        EpisodeComponentAssignmentWorker(UpdateMemoryHierarchy { batches += it; MemoryHierarchyUpdate.Skipped }, batchSize = 7)
            .use { it.runRound() }
        assertEquals(listOf(7), batches)
    }

    @Test
    fun `a failing round is contained`() {
        EpisodeComponentAssignmentWorker(UpdateMemoryHierarchy { error("store unavailable") }).use { it.runRound() }
    }
}
