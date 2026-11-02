package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeViewerTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.application.port.output.component.ComponentJoin
import com.homeassistant.application.port.output.component.ComponentMaintenanceStore
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.component.ComponentCentroid
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentRepository(private val db: Database) : ComponentMaintenanceStore {
    override fun centroids(): Map<Int, ComponentCentroid> = transaction(db) {
        ComponentTable.selectAll().orderBy(ComponentTable.id).associate { it[ComponentTable.id] to centroid(it) }
    }

    override fun read(componentIds: Collection<Int>): List<Component> = transaction(db) {
        if (componentIds.isEmpty()) return@transaction emptyList()
        val members = ComponentMembershipTable.selectAll()
            .where { ComponentMembershipTable.componentId inList componentIds }
            .orderBy(ComponentMembershipTable.episodeId)
            .map { it[ComponentMembershipTable.componentId] to it[ComponentMembershipTable.episodeId] }
        val access = accessOf(members.map { it.second })
        val slices = members.groupBy({ it.first }, { it.second })
        ComponentTable.selectAll().where { ComponentTable.id inList componentIds }.orderBy(ComponentTable.id).map { row ->
            val id = row[ComponentTable.id]
            Component(id, centroid(row), slices[id].orEmpty().groupBy { access.getValue(it) })
        }
    }

    override fun join(episodeId: Int, joins: List<ComponentJoin>): Boolean = transaction(db) {
        require(joins.isNotEmpty() && joins.distinctBy { it.componentId }.size == joins.size) { "a join names distinct components" }
        requireEpisode(episodeId)
        for (join in joins) {
            val changed = ComponentTable.update({ (ComponentTable.id eq join.componentId) and (ComponentTable.episodeCount eq join.expected.count) }) {
                it[vectorSum] = encode(join.updated.sum)
                it[episodeCount] = join.updated.count
            }
            if (changed == 0) {
                rollback()
                return@transaction false
            }
            addMembership(join.componentId, episodeId)
        }
        true
    }

    override fun create(episodeId: Int, centroid: ComponentCentroid): Int = transaction(db) {
        require(centroid.count == 1) { "a new component starts with one episode" }
        requireEpisode(episodeId)
        val id = ComponentTable.insert {
            it[vectorSum] = encode(centroid.sum)
            it[episodeCount] = centroid.count
        }[ComponentTable.id]
        addMembership(id, episodeId)
        id
    }

    private fun requireEpisode(episodeId: Int) {
        require(EpisodeTable.select(EpisodeTable.id).where { EpisodeTable.id eq episodeId }.any()) { "episode reference does not exist" }
    }

    /** An episode already in the Component violates the membership key and rolls back the whole write. */
    private fun addMembership(componentId: Int, episodeId: Int) {
        ComponentMembershipTable.insert {
            it[ComponentMembershipTable.componentId] = componentId
            it[ComponentMembershipTable.episodeId] = episodeId
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

    private fun centroid(row: ResultRow) = ComponentCentroid(
        Json.parseToJsonElement(row[ComponentTable.vectorSum]).jsonArray.map { it.jsonPrimitive.double },
        row[ComponentTable.episodeCount],
    )

    private fun encode(sum: List<Double>): String = JsonArray(sum.map(::JsonPrimitive)).toString()
}
