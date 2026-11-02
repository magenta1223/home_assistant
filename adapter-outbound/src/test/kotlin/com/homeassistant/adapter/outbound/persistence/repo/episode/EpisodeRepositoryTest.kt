package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EpisodeRepositoryTest {
    @Test
    fun `legacy private episode migrates to creator-only restricted access`() {
        val databasePath = Files.createTempFile("legacy-private-episode", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val episodes = EpisodeRepository(database)
            val creator = UserId("member-1")
            val evidence = sourceRecords.saveAll(
                SourceDescriptor("test", "legacy"),
                listOf(SourceRecordDraft("legacy", "legacy")),
            ).recordsToAnalyze.single()
            val episode = episodes.write(episodeProposal(evidence.id, "legacy"), creator)
            transaction(database) {
                EpisodeTable.update({ EpisodeTable.id eq episode.id }) { it[visibility] = "PRIVATE" }
                SourceRecordTable.update({ SourceRecordTable.id eq evidence.id }) {
                    it[audienceExplicit] = false
                }
            }

            val migratedDatabase = DatabaseFactory.init(databasePath.toString())
            val migratedEpisodes = EpisodeRepository(migratedDatabase)
            val migratedSource = SourceRecordRepositoryImpl(migratedDatabase)

            assertEquals(EpisodeVisibility.RESTRICTED, migratedEpisodes.getEpisodes(creator).single().visibility)
            assertEquals(setOf(creator.value), migratedEpisodes.getEpisodes(creator).single().allowedUserIds)
            assertEquals(0, migratedEpisodes.getEpisodes(UserId("member-2")).size)
            assertEquals(
                EpisodeAccess.restricted(listOf(creator)),
                migratedSource.findBySource(SourceDescriptor("test", "legacy")).single().access,
            )
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `stores the title and one creation time and reads them from an existing episode`() {
        val databasePath = Files.createTempFile("episode-created-at", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val createdAt = Instant.parse("2026-08-08T01:02:03Z")
            val episodes = EpisodeRepository(database, Clock.fixed(createdAt, ZoneOffset.UTC))
            val userId = UserId("member-1")
            val record = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "created-at"),
                listOf(SourceRecordDraft("episode", "episode")),
            ).recordsToAnalyze.single()

            val saved = episodes.write(episodeProposal(record.id, "episode"), userId)
            val laterReader = EpisodeRepository(
                database,
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC),
            )
            val stored = laterReader.getEpisodes(userId).single()

            assertEquals(createdAt.toEpochMilli(), saved.createdAt)
            assertEquals(saved.createdAt, stored.createdAt)
            assertEquals("title", stored.title)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `restricted episode is visible to every selected user and nobody else`() {
        val databasePath = Files.createTempFile("private-episode", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val episodes = EpisodeRepository(database)
            val uploader = UserId("member-1")
            val participant = UserId("member-2")
            val otherMember = UserId("member-3")
            val record = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "visibility"),
                listOf(SourceRecordDraft("private", "private")),
                EpisodeAccess.restricted(listOf(uploader, participant)),
            ).recordsToAnalyze.single()
            episodes.write(
                episodeProposal(record.id, "private"),
                uploader,
            )

            assertEquals(1, episodes.getEpisodes(uploader).size)
            assertEquals(1, episodes.getEpisodes(participant).size)
            assertEquals(0, episodes.getEpisodes(otherMember).size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `episode backed by multiple sources receives their viewer intersection`() {
        val databasePath = Files.createTempFile("episode-access-intersection", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val episodes = EpisodeRepository(database)
            val member1 = UserId("member-1")
            val member2 = UserId("member-2")
            val member3 = UserId("member-3")
            val first = sourceRecords.saveAll(
                SourceDescriptor("test", "first"),
                listOf(SourceRecordDraft("first", "first")),
                EpisodeAccess.restricted(listOf(member1, member2)),
            ).recordsToAnalyze.single()
            val second = sourceRecords.saveAll(
                SourceDescriptor("test", "second"),
                listOf(SourceRecordDraft("second", "second")),
                EpisodeAccess.restricted(listOf(member2, member3)),
            ).recordsToAnalyze.single()
            val proposal = episodeProposal(first.id, "combined").copy(evidenceIds = listOf(first.id, second.id))

            episodes.write(proposal, member1)

            assertEquals(0, episodes.getEpisodes(member1).size)
            assertEquals(1, episodes.getEpisodes(member2).size)
            assertEquals(0, episodes.getEpisodes(member3).size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun episodeProposal(evidenceId: Int, content: String) = EpisodeProposal(
        title = "title",
        content = content,
        evidenceIds = listOf(evidenceId),
    )

    private fun EpisodeRepository.write(proposal: EpisodeProposal, createdBy: UserId) = commit(
        createdBy,
        listOf(IdempotentEpisodeProposal("test:${proposal.evidenceIds}:${proposal.content}", proposal)),
        proposal.evidenceIds,
    ).single()
}
