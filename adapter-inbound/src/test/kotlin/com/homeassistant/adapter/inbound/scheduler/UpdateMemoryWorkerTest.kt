package com.homeassistant.adapter.inbound.scheduler

import com.homeassistant.application.port.inbound.memoryextraction.MemoryUpdateResult
import com.homeassistant.application.port.inbound.memoryextraction.UpdateMemory
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateMemoryWorkerTest {
    @Test
    fun `a round updates the hierarchy with the batch size`() {
        val batches = mutableListOf<Int>()
        UpdateMemoryWorker(UpdateMemory { batches += it; MemoryUpdateResult.Skipped }, batchSize = 7)
            .runRound()
        assertEquals(listOf(7), batches)
    }

    @Test
    fun `a failing round is contained`() {
        UpdateMemoryWorker(UpdateMemory { error("store unavailable") }).runRound()
    }
}
