package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryViewerTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import com.homeassistant.application.port.output.topic.TopicJoin
import com.homeassistant.application.port.output.topic.TopicMaintenanceStore
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicCentroid
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class TopicRepository(private val db: Database) : TopicMaintenanceStore {
    override fun centroids(): Map<Int, TopicCentroid> = transaction(db) {
        TopicTable.selectAll().orderBy(TopicTable.id).associate { it[TopicTable.id] to centroid(it) }
    }

    override fun read(topicIds: Collection<Int>): List<Topic> = transaction(db) {
        if (topicIds.isEmpty()) return@transaction emptyList()
        val members = TopicMembershipTable.selectAll()
            .where { TopicMembershipTable.topicId inList topicIds }
            .orderBy(TopicMembershipTable.memoryId)
            .map { it[TopicMembershipTable.topicId] to it[TopicMembershipTable.memoryId] }
        val access = accessOf(members.map { it.second })
        val slices = members.groupBy({ it.first }, { it.second })
        TopicTable.selectAll().where { TopicTable.id inList topicIds }.orderBy(TopicTable.id).map { row ->
            val id = row[TopicTable.id]
            Topic(id, centroid(row), slices[id].orEmpty().groupBy { access.getValue(it) })
        }
    }

    override fun join(memoryId: Int, joins: List<TopicJoin>): Boolean = transaction(db) {
        require(joins.isNotEmpty() && joins.distinctBy { it.topicId }.size == joins.size) { "a join names distinct topics" }
        requireMemory(memoryId)
        for (join in joins) {
            val changed = TopicTable.update({ (TopicTable.id eq join.topicId) and (TopicTable.memoryCount eq join.expected.count) }) {
                it[vectorSum] = encode(join.updated.sum)
                it[memoryCount] = join.updated.count
            }
            if (changed == 0) {
                rollback()
                return@transaction false
            }
            addMembership(join.topicId, memoryId)
        }
        true
    }

    override fun create(memoryId: Int, centroid: TopicCentroid): Int = transaction(db) {
        require(centroid.count == 1) { "a new topic starts with one memory" }
        requireMemory(memoryId)
        val id = TopicTable.insert {
            it[vectorSum] = encode(centroid.sum)
            it[memoryCount] = centroid.count
        }[TopicTable.id]
        addMembership(id, memoryId)
        id
    }

    private fun requireMemory(memoryId: Int) {
        require(MemoryTable.select(MemoryTable.id).where { MemoryTable.id eq memoryId }.any()) { "memory reference does not exist" }
    }

    /** A memory already in the Topic violates the membership key and rolls back the whole write. */
    private fun addMembership(topicId: Int, memoryId: Int) {
        TopicMembershipTable.insert {
            it[TopicMembershipTable.topicId] = topicId
            it[TopicMembershipTable.memoryId] = memoryId
        }
    }

    private fun accessOf(memoryIds: List<Int>): Map<Int, MemoryAccess> {
        if (memoryIds.isEmpty()) return emptyMap()
        val viewers = MemoryViewerTable.selectAll().where { MemoryViewerTable.memoryId inList memoryIds }
            .groupBy({ it[MemoryViewerTable.memoryId] }, { it[MemoryViewerTable.userId] })
        return MemoryTable.select(MemoryTable.id, MemoryTable.visibility).where { MemoryTable.id inList memoryIds }.associate { row ->
            val id = row[MemoryTable.id]
            val visibility = MemoryVisibility.valueOf(row[MemoryTable.visibility])
            id to MemoryAccess(visibility, if (visibility == MemoryVisibility.PUBLIC) emptySet() else viewers[id].orEmpty().toSet())
        }
    }

    private fun centroid(row: ResultRow) = TopicCentroid(
        Json.parseToJsonElement(row[TopicTable.vectorSum]).jsonArray.map { it.jsonPrimitive.double },
        row[TopicTable.memoryCount],
    )

    private fun encode(sum: List<Double>): String = JsonArray(sum.map(::JsonPrimitive)).toString()
}
