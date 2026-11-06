package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentExtractionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeViewerTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeAccessCondition
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentRepositoryImpl(private val db: Database) : ComponentRepository {
    override fun findByIds(ids: Collection<Int>): List<Component> = transaction(db) { withIds(ids) }

    override fun findCovering(access: EpisodeAccess): List<Component> = transaction(db) {
        fromEpisodes(EpisodeAccessCondition.covering(access))
    }

    override fun findVisibleTo(userId: UserId): List<Component> = transaction(db) {
        fromEpisodes(EpisodeAccessCondition.visibleTo(userId))
    }

    /** A second extraction of the same episode violates the extraction key and rolls back the whole write. */
    override fun save(extraction: EpisodeComponents): List<Component> = transaction(db) {
        val episodeId = extraction.episodeId
        require(extraction.statements.none(String::isBlank)) { "a component statement must not be blank" }
        require(EpisodeTable.select(EpisodeTable.id).where { EpisodeTable.id eq episodeId }.any()) { "episode reference does not exist" }
        ComponentExtractionTable.insert { it[ComponentExtractionTable.episodeId] = episodeId }
        withIds(extraction.statements.map { text ->
            ComponentTable.insert {
                it[statement] = text
                it[sourceEpisodeId] = episodeId
            }[ComponentTable.id]
        })
    }

    private fun withIds(ids: Collection<Int>): List<Component> =
        if (ids.isEmpty()) emptyList() else components(ComponentTable.selectAll().where { ComponentTable.id inList ids })

    private fun fromEpisodes(episodes: Op<Boolean>): List<Component> = components(ComponentTable.selectAll().where {
        ComponentTable.sourceEpisodeId inSubQuery EpisodeTable.select(EpisodeTable.id).where(episodes)
    })

    private fun components(query: Query): List<Component> {
        val rows = query.orderBy(ComponentTable.id).toList()
        val access = accessOf(rows.map { it[ComponentTable.sourceEpisodeId] }.distinct())
        return rows.map { row ->
            val source = row[ComponentTable.sourceEpisodeId]
            Component(row[ComponentTable.id], row[ComponentTable.statement], source, access.getValue(source))
        }
    }

    private fun accessOf(episodeIds: List<Int>): Map<Int, EpisodeAccess> {
        if (episodeIds.isEmpty()) return emptyMap()
        val viewers = EpisodeViewerTable.selectAll().where { EpisodeViewerTable.episodeId inList episodeIds }
            .groupBy({ it[EpisodeViewerTable.episodeId] }, { it[EpisodeViewerTable.userId] })
        return EpisodeTable.select(EpisodeTable.id, EpisodeTable.visibility).where { EpisodeTable.id inList episodeIds }.associate { row ->
            val id = row[EpisodeTable.id]
            val visibility = EpisodeVisibility.valueOf(row[EpisodeTable.visibility])
            id to EpisodeAccess(visibility, if (visibility == EpisodeVisibility.PUBLIC) emptySet() else viewers[id].orEmpty().toSet())
        }
    }
}
