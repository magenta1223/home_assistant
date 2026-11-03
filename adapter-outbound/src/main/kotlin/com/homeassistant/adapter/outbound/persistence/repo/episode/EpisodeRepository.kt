package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeViewerTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordViewerTable
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentAssignmentOutboxRepository
import com.homeassistant.application.port.output.episode.EpisodeReader
import com.homeassistant.application.port.output.episode.EpisodeWriter
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

internal class EpisodeRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : EpisodeReader, EpisodeWriter {

    override fun getEpisodes(userId: UserId): List<Episode> = transaction(db) {
        EpisodeTable
            .selectAll()
            .map { it.toEpisode() }
            .filter { it.isVisibleTo(userId) }
    }

    override fun commit(
        createdBy: UserId,
        proposals: List<EpisodeProposal>,
        analyzedSourceRecordIds: Collection<Int>,
    ): List<Episode> = transaction(db) {
        val recordIds = analyzedSourceRecordIds.distinct()
        val existingRecordIds = if (recordIds.isEmpty()) emptySet() else {
            SourceRecordTable.select(SourceRecordTable.id)
                .where { SourceRecordTable.id inList recordIds }
                .mapTo(mutableSetOf()) { it[SourceRecordTable.id] }
        }
        require(existingRecordIds.size == recordIds.size) { "Analyzed source records do not all exist" }
        proposals.forEach { item ->
            require(item.evidenceIds.isNotEmpty()) { "episode evidence is required" }
            require(item.evidenceIds.all { it in existingRecordIds }) {
                "Episode evidence must belong to the analyzed source batch"
            }
        }

        val accessByRecordId = existingRecordIds.associateWith(::sourceAccess)

        val now = clock.millis()
        val episodes = proposals.map { item ->
            val existing = EpisodeTable.selectAll()
                .where { EpisodeTable.idempotencyKey eq item.toIdempotencyKey(createdBy) }
                .singleOrNull()
            if (existing != null) {
                ensureOutbox(existing[EpisodeTable.id], now)
                existing.toEpisode()
            } else {
                val access = EpisodeAccess.intersection(
                    item.evidenceIds.distinct().map(accessByRecordId::getValue),
                )
                val episodeId = EpisodeTable.insert {
                    it[createdByUserId] = createdBy.value
                    it[title] = item.title
                    it[content] = item.content
                    it[visibility] = access.visibility.name
                    it[idempotencyKey] = item.toIdempotencyKey(createdBy)
                    it[createdAt] = now
                }[EpisodeTable.id]
                item.evidenceIds.distinct().forEach { sourceRecordId ->
                    EpisodeEvidenceTable.insert {
                        it[EpisodeEvidenceTable.episodeId] = episodeId
                        it[EpisodeEvidenceTable.sourceRecordId] = sourceRecordId
                    }
                }
                access.allowedUserIds.forEach { allowedUserId ->
                    EpisodeViewerTable.insert {
                        it[EpisodeViewerTable.episodeId] = episodeId
                        it[userId] = allowedUserId
                    }
                }
                ensureOutbox(episodeId, now)
                item.toEpisode(createdBy, episodeId, now, access)
            }
        }
        if (recordIds.isNotEmpty()) {
            SourceRecordTable.update({ SourceRecordTable.id inList recordIds }) {
                it[analysisStatus] = SourceRecordAnalysisStatus.ANALYZED.name
            }
        }
        episodes
    }

    private fun ensureOutbox(episodeId: Int, now: Long) {
        ComponentAssignmentOutboxRepository.enqueue(episodeId, now)
    }

    internal fun ResultRow.toEpisode(): Episode {
        val episodeId = this[EpisodeTable.id]
        val evidenceRefs = EpisodeEvidenceTable.select(EpisodeEvidenceTable.sourceRecordId)
            .where { EpisodeEvidenceTable.episodeId eq episodeId }
            .map { it[EpisodeEvidenceTable.sourceRecordId] }
        return Episode(
            id = episodeId,
            createdByUserId = this[EpisodeTable.createdByUserId],
            title = this[EpisodeTable.title],
            content = this[EpisodeTable.content],
            visibility = EpisodeVisibility.valueOf(this[EpisodeTable.visibility]),
            allowedUserIds = EpisodeViewerTable.select(EpisodeViewerTable.userId)
                .where { EpisodeViewerTable.episodeId eq episodeId }
                .mapTo(linkedSetOf()) { it[EpisodeViewerTable.userId] },
            evidenceRefs = evidenceRefs,
            createdAt = this[EpisodeTable.createdAt],
        )
    }

    private fun EpisodeProposal.toEpisode(
        userId: UserId,
        episodeId: Int,
        createdAt: Long,
        access: EpisodeAccess,
    ): Episode {
        return Episode(
            id = episodeId,
            createdByUserId = userId.value,
            title = title,
            content = content,
            visibility = access.visibility,
            allowedUserIds = access.allowedUserIds,
            evidenceRefs = evidenceIds,
            createdAt = createdAt,
        )
    }

    private fun sourceAccess(sourceRecordId: Int): EpisodeAccess {
        val visibility = SourceRecordTable.select(SourceRecordTable.visibility)
            .where { SourceRecordTable.id eq sourceRecordId }
            .single()[SourceRecordTable.visibility]
            .let(EpisodeVisibility::valueOf)
        val viewers = if (visibility == EpisodeVisibility.PUBLIC) emptySet() else {
            SourceRecordViewerTable.select(SourceRecordViewerTable.userId)
                .where { SourceRecordViewerTable.sourceRecordId eq sourceRecordId }
                .mapTo(linkedSetOf()) { it[SourceRecordViewerTable.userId] }
        }
        return EpisodeAccess(visibility, viewers)
    }

    /**
     * Includes the creator, content and evidence set. Source access is immutable and is
     * resolved from evidence by the atomic writer. Length-prefixing avoids
     * delimiter ambiguity; sorting evidence makes extractor ordering irrelevant across retries.
     */
    private fun EpisodeProposal.toIdempotencyKey(createdBy: UserId): String {
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

}
