package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.memoryextraction.GroupOrganizationResult
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeOrganizationResult
import com.homeassistant.application.port.inbound.memoryextraction.OrganizePendingEpisodes
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeComponentAssignmentWorkerTest {
    @Test
    fun `a round without the assignment services does not touch the outbox`() {
        val organize = RecordingOrganize(ready = false)
        EpisodeComponentAssignmentWorker(organize).use { it.runRound() }
        assertEquals(listOf("isReady"), organize.calls)
    }

    @Test
    fun `a ready round processes one batch`() {
        val organize = RecordingOrganize(ready = true)
        EpisodeComponentAssignmentWorker(organize, batchSize = 7).use { it.runRound() }
        assertEquals(listOf("isReady", "execute:7"), organize.calls)
    }

    @Test
    fun `queuing unassigned episodes does not need the assignment services`() {
        val organize = RecordingOrganize(ready = false)
        EpisodeComponentAssignmentWorker(organize).use { it.enqueueUnassigned() }
        assertEquals(listOf("enqueueUnassigned"), organize.calls)
    }

    @Test
    fun `a failing readiness check is contained`() {
        val organize = object : OrganizePendingEpisodes {
            override fun isReady(): Boolean = error("probe failed")
            override fun execute(limit: Int): EpisodeOrganizationResult = error("must not run")
            override fun enqueueUnassigned(): Int = 0
        }
        EpisodeComponentAssignmentWorker(organize).use { it.runRound() }
    }

    @Test
    fun `a failing round is contained`() {
        val organize = object : OrganizePendingEpisodes {
            override fun isReady() = true
            override fun execute(limit: Int): EpisodeOrganizationResult = error("store unavailable")
            override fun enqueueUnassigned(): Int = error("store unavailable")
        }
        EpisodeComponentAssignmentWorker(organize).use {
            it.enqueueUnassigned()
            it.runRound()
        }
    }

    private class RecordingOrganize(private val ready: Boolean) : OrganizePendingEpisodes {
        val calls = mutableListOf<String>()

        override fun isReady(): Boolean {
            calls += "isReady"
            return ready
        }

        override fun execute(limit: Int): EpisodeOrganizationResult {
            calls += "execute:$limit"
            return EpisodeOrganizationResult(emptyList(), GroupOrganizationResult.Completed(emptyList()), emptyList())
        }

        override fun enqueueUnassigned(): Int {
            calls += "enqueueUnassigned"
            return 0
        }
    }
}
