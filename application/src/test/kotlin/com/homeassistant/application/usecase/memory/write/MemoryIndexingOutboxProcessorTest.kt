package com.homeassistant.application.usecase.memory.write

import com.homeassistant.application.port.output.memory.write.MemoryIndexingOutbox
import com.homeassistant.application.port.output.memory.write.MemoryIndexingTask
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryIndexingOutboxProcessorTest {
    @Test
    fun `completion carries the claimed attempt generation`() {
        val outbox = RecordingOutbox(task(attempt = 7))
        val processor = processor(outbox) { true }

        assertEquals(1, processor.processAvailable().completed)
        assertEquals(7, outbox.completedAttempt)
    }

    @Test
    fun `failure carries the claimed attempt generation`() {
        val outbox = RecordingOutbox(task(attempt = 9))
        val processor = processor(outbox) { false }

        assertEquals(1, processor.processAvailable().failed)
        assertEquals(9, outbox.failedAttempt)
    }

    @Test
    fun `rejected stale completion is reported as superseded rather than completed`() {
        val outbox = RecordingOutbox(task(attempt = 11), transitionAccepted = false)

        val result = processor(outbox) { true }.processAvailable()

        assertEquals(0, result.completed)
        assertEquals(0, result.failed)
        assertEquals(1, result.superseded)
    }

    @Test
    fun `rejected stale failure is reported as superseded rather than failed`() {
        val outbox = RecordingOutbox(task(attempt = 12), transitionAccepted = false)

        val result = processor(outbox) { false }.processAvailable()

        assertEquals(0, result.completed)
        assertEquals(0, result.failed)
        assertEquals(1, result.superseded)
    }

    private fun processor(
        outbox: MemoryIndexingOutbox,
        index: (Memory) -> Boolean,
    ) = MemoryIndexingOutboxProcessor(
        outbox = outbox,
        indexWriter = index,
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

    private fun task(attempt: Int) = MemoryIndexingTask(
        outboxId = 1,
        attempt = attempt,
        memory = Memory(
            id = 10,
            createdByUserId = "member-1",
            content = "content",
            subject = "subject",
            memoryType = MemoryType.REFERENCE,
            certainty = MemoryCertainty.OBSERVED,
            visibility = MemoryVisibility.PUBLIC,
            evidenceRefs = listOf(1),
            createdAt = 1,
        ),
    )

    private class RecordingOutbox(
        private val task: MemoryIndexingTask,
        private val transitionAccepted: Boolean = true,
    ) : MemoryIndexingOutbox {
        var completedAttempt: Int? = null
        var failedAttempt: Int? = null

        override fun claimReady(
            limit: Int,
            now: Long,
            retryBefore: Long,
            staleProcessingBefore: Long,
        ) = listOf(task)

        override fun markCompleted(outboxId: Int, expectedAttempt: Int, now: Long): Boolean {
            completedAttempt = expectedAttempt
            return transitionAccepted
        }

        override fun markFailed(outboxId: Int, expectedAttempt: Int, error: String, now: Long): Boolean {
            failedAttempt = expectedAttempt
            return transitionAccepted
        }

        override fun enqueueAll(now: Long): Int = 1
    }
}
