package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.repo.embedding.ComponentEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.embedding.EmbeddingVectorCodec
import com.homeassistant.domain.embedding.EmbeddingGeometry
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeAccessCondition
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeConflict
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshot
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.StoredGroup
import com.homeassistant.domain.group.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

internal class GroupRepositoryImpl(private val db: Database) : GroupRepository {
    override fun findSnapshot(): GroupSnapshot = transaction(db) {
        val memberships = GroupMembershipTable.selectAll().toList()
        val keys = memberships.map { it[GroupMembershipTable.embeddingKey] }
        val embeddings = ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key inList keys }
            .map { ComponentEmbeddingRepositoryImpl.decode(it) }.associateBy { it.key.componentId }
        val groups = storedGroups(memberships)
        check(memberships.size == embeddings.size) { "group embedding missing" }
        GroupSnapshot(groups, embeddings)
    }

    override fun findVisibleTo(userId: UserId): List<StoredGroup> = transaction(db) {
        storedGroups(GroupMembershipTable.selectAll().where {
            GroupMembershipTable.componentId inSubQuery ComponentTable.select(ComponentTable.id)
                .where(ComponentRepositoryImpl.sourcedFrom(EpisodeAccessCondition.visibleTo(userId)))
        }.toList())
    }

    /** The Groups of [memberships], each holding only its Components among them; a Group without one is left out. */
    private fun storedGroups(memberships: List<ResultRow>): List<StoredGroup> {
        val byGroup = memberships.groupBy { it[GroupMembershipTable.groupId] }
        return GroupTable.selectAll().where { GroupTable.id inList byGroup.keys }.orderBy(GroupTable.id).map { row ->
            StoredGroup(
                Group(
                    row[GroupTable.id], byGroup.getValue(row[GroupTable.id])
                    .map { it[GroupMembershipTable.componentId] }.toSet()
                ), row[GroupTable.revision],
                EmbeddingVectorCodec.decode(row[GroupTable.centroid])
            )
        }
    }

    override fun save(change: GroupChange): List<Group> = try {
        transaction(db) {
            verifyRevisionAndPartition(change)
            verifyEmbeddings(change)
            replaceGroups(change).zip(change.resultingGroups) { id, result -> Group(id, result.componentIds) }
        }
    } catch (error: ExposedSQLException) {
        if (!error.isBusy()) throw error
        throw GroupChangeConflict("a concurrent write held the database", error)
    }

    private fun verifyRevisionAndPartition(change: GroupChange) {
        val revisions = GroupTable.selectAll().where { GroupTable.id inList change.expectedGroups.keys }
            .associate { it[GroupTable.id] to it[GroupTable.revision] }
        if (revisions != change.expectedGroups) throw concurrentChange()
        val consumed = GroupMembershipTable.selectAll().where { GroupMembershipTable.groupId inList revisions.keys }
            .map { it[GroupMembershipTable.componentId] }.toSet()
        val assignedIncoming = GroupMembershipTable.selectAll().where { GroupMembershipTable.componentId inList change.incomingComponentIds }.any()
        if (assignedIncoming) throw concurrentChange()
        require(consumed.intersect(change.incomingComponentIds).isEmpty())
        ComponentPartition(change.resultingGroups.map { it.componentIds }).requireConserves(consumed + change.incomingComponentIds)
        val components = ComponentTable.select(ComponentTable.id).where { ComponentTable.id inList change.embeddings.keys }
            .map { it[ComponentTable.id] }.toSet()
        require(components == change.embeddings.keys) { "component does not exist" }
    }

    private fun verifyEmbeddings(change: GroupChange) {
        val stored = ComponentEmbeddingTable.selectAll().where {
            ComponentEmbeddingTable.key inList change.embeddings.values.map { ComponentEmbeddingRepositoryImpl.key(it.key) }
        }.map { ComponentEmbeddingRepositoryImpl.decode(it) }.associateBy { it.key.componentId }
        require(stored == change.embeddings) { "expected immutable vectors are missing or changed" }
        val versions = ComponentTable.selectAll().where { ComponentTable.id inList stored.keys }
            .associate { it[ComponentTable.id] to ComponentEmbeddingRepositoryImpl.version(it) }
        if (stored.values.any { versions[it.key.componentId] != it.key.version }) throw concurrentChange()
        val activeKeys = GroupMembershipTable.selectAll().map { it[GroupMembershipTable.embeddingKey] }
        val active = ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key inList activeKeys }
            .map { ComponentEmbeddingRepositoryImpl.decode(it) }
        val combined = active + stored.values
        if (combined.map { it.key.space }.distinct().size != 1 || combined.map { it.vector.dimension }.distinct().size != 1) {
            throw GroupRebuildRequired("cannot mix group embedding models, formats or dimensions")
        }
        change.resultingGroups.forEach { result ->
            val centroid = EmbeddingGeometry.centroid(result.componentIds.sorted().map { stored.getValue(it).vector })
            require(centroid == result.centroid) { "centroid does not match membership vectors" }
        }
    }

    private fun replaceGroups(change: GroupChange): List<Int> {
        GroupMembershipTable.deleteWhere { groupId inList change.expectedGroups.keys }
        val ids = change.resultingGroups.map { result ->
            val id = when (val target = result.target) {
                is GroupTarget.Existing -> {
                    GroupTable.update({ GroupTable.id eq target.id }) {
                        it[revision] = change.expectedGroups.getValue(target.id) + 1
                        it[centroid] = EmbeddingVectorCodec.encode(result.centroid)
                    }
                    target.id
                }
                is GroupTarget.Created -> GroupTable.insert {
                    it[revision] = 1
                    it[centroid] = EmbeddingVectorCodec.encode(result.centroid)
                }[GroupTable.id]
            }
            result.componentIds.sorted().forEach { component ->
                GroupMembershipTable.insert {
                    it[componentId] = component
                    it[groupId] = id
                    it[embeddingKey] = ComponentEmbeddingRepositoryImpl.key(change.embeddings.getValue(component).key)
                }
            }
            id
        }
        GroupTable.deleteWhere { id inList (change.expectedGroups.keys - ids.toSet()) }
        return ids
    }

    private fun concurrentChange() = GroupChangeConflict("a concurrent change updated the expected Groups")

    /** True when SQLite refused the write because another connection held the database lock. */
    private fun ExposedSQLException.isBusy(): Boolean =
        (cause as? SQLiteException)?.resultCode?.let { it.code and 0xFF == SQLiteErrorCode.SQLITE_BUSY.code } == true
}
