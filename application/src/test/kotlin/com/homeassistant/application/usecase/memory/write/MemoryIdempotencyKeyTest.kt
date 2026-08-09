package com.homeassistant.application.usecase.memory.write

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MemoryIdempotencyKeyTest {
    private val user = UserId("member-1")
    private val proposal = MemoryProposal(
        content = "A fact",
        subject = "subject",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = listOf(1, 2),
    )

    @Test
    fun `evidence order and duplicate ids do not change retry identity`() {
        assertEquals(
            proposal.idempotencyKey(user),
            proposal.copy(evidenceIds = listOf(2, 1, 1)).idempotencyKey(user),
        )
    }

    @Test
    fun `creator evidence and every meaning field participate in retry identity`() {
        val base = proposal.idempotencyKey(user)
        val variants = listOf(
            proposal.copy(content = "Another fact").idempotencyKey(user),
            proposal.copy(subject = "another subject").idempotencyKey(user),
            proposal.copy(memoryType = MemoryType.EVENT).idempotencyKey(user),
            proposal.copy(certainty = MemoryCertainty.INFERRED).idempotencyKey(user),
            proposal.copy(evidenceIds = listOf(1, 3)).idempotencyKey(user),
            proposal.idempotencyKey(UserId("member-2")),
        )

        variants.forEach { assertNotEquals(base, it) }
    }
}
