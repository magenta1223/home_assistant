package com.homeassistant.app.services

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.topic.MemoryOrganizationResult
import com.homeassistant.application.port.input.topic.OrganizePendingMemories
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryTopicAssignmentWorkerTest {
    @Test
    fun `a round without the assignment services does not touch the outbox`() {
        val organize = RecordingOrganize(ready = false)
        MemoryTopicAssignmentWorker(organize).use { it.runRound() }
        assertEquals(listOf("isReady"), organize.calls)
    }

    @Test
    fun `a ready round processes one batch`() {
        val organize = RecordingOrganize(ready = true)
        MemoryTopicAssignmentWorker(organize, batchSize = 7).use { it.runRound() }
        assertEquals(listOf("isReady", "execute:7"), organize.calls)
    }

    @Test
    fun `queuing unassigned memories does not need the assignment services`() {
        val organize = RecordingOrganize(ready = false)
        MemoryTopicAssignmentWorker(organize).use { it.enqueueUnassigned() }
        assertEquals(listOf("enqueueUnassigned"), organize.calls)
    }

    @Test
    fun `a failing readiness check is contained`() {
        val organize = object : OrganizePendingMemories {
            override fun isReady(): Boolean = error("probe failed")
            override fun execute(limit: Int): MemoryOrganizationResult = error("must not run")
            override fun enqueueUnassigned(): Int = 0
        }
        MemoryTopicAssignmentWorker(organize).use { it.runRound() }
    }

    @Test
    fun `a failing round is contained`() {
        val organize = object : OrganizePendingMemories {
            override fun isReady() = true
            override fun execute(limit: Int): MemoryOrganizationResult = error("store unavailable")
            override fun enqueueUnassigned(): Int = error("store unavailable")
        }
        MemoryTopicAssignmentWorker(organize).use {
            it.enqueueUnassigned()
            it.runRound()
        }
    }

    private class RecordingOrganize(private val ready: Boolean) : OrganizePendingMemories {
        val calls = mutableListOf<String>()

        override fun isReady(): Boolean {
            calls += "isReady"
            return ready
        }

        override fun execute(limit: Int): MemoryOrganizationResult {
            calls += "execute:$limit"
            return MemoryOrganizationResult(emptyList(), emptyList(), GroupOrganizationResult.Completed(emptyList()))
        }

        override fun enqueueUnassigned(): Int {
            calls += "enqueueUnassigned"
            return 0
        }
    }
}
