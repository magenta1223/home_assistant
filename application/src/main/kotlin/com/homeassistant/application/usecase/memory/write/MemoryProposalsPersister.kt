package com.homeassistant.application.usecase.memory.write

import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryProposal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Persists flat memory proposals immediately without a review stage. */
class MemoryProposalsPersister(
    private val batchWriter: CanonicalMemoryBatchWriter,
) {

    fun persist(
        userId: UserId,
        proposals: List<MemoryProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Memory> {
        val idempotentProposals = proposals
            .map { proposal -> IdempotentMemoryProposal(proposal.idempotencyKey(userId), proposal) }
            .distinctBy(IdempotentMemoryProposal::idempotencyKey)
        return batchWriter.commit(userId, idempotentProposals, analyzedSourceRecordIds)
    }
}

/**
 * Includes every proposed meaning field and the evidence set. Source access is immutable and is
 * resolved from evidence by the atomic writer. Length-prefixing avoids
 * delimiter ambiguity; sorting evidence makes extractor ordering irrelevant across retries.
 */
internal fun MemoryProposal.idempotencyKey(createdBy: UserId): String {
    val digest = MessageDigest.getInstance("SHA-256")
    sequenceOf(
        createdBy.value,
        content.trim(),
        subject.trim(),
        memoryType.name,
        certainty.name,
        evidenceIds.distinct().sorted().joinToString(","),
    ).forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
