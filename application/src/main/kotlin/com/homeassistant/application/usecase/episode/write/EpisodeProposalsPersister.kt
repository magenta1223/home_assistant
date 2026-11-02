package com.homeassistant.application.usecase.episode.write

import com.homeassistant.application.port.output.episode.write.CanonicalEpisodeBatchWriter
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeProposal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Persists flat episode proposals immediately without a review stage. */
class EpisodeProposalsPersister(
    private val batchWriter: CanonicalEpisodeBatchWriter,
) {

    fun persist(
        userId: UserId,
        proposals: List<EpisodeProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Episode> {
        val idempotentProposals = proposals
            .map { proposal -> IdempotentEpisodeProposal(proposal.idempotencyKey(userId), proposal) }
            .distinctBy(IdempotentEpisodeProposal::idempotencyKey)
        return batchWriter.commit(userId, idempotentProposals, analyzedSourceRecordIds)
    }
}

/**
 * Includes the creator, content and evidence set. Source access is immutable and is
 * resolved from evidence by the atomic writer. Length-prefixing avoids
 * delimiter ambiguity; sorting evidence makes extractor ordering irrelevant across retries.
 */
internal fun EpisodeProposal.idempotencyKey(createdBy: UserId): String {
    val digest = MessageDigest.getInstance("SHA-256")
    sequenceOf(
        createdBy.value,
        content.trim(),
        evidenceIds.distinct().sorted().joinToString(","),
    ).forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
