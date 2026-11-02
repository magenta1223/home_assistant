package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

internal class GroupRepository(private val db: Database) : PendingComponentSource, GroupReader, GroupSnapshotReader, GroupChangeWriter {
    override fun pending(limit: Int): List<PendingComponent> = transaction(db) {
        require(limit > 0)
        ComponentTable.selectAll().where {
            notExists(GroupMembershipTable.selectAll().where { GroupMembershipTable.componentId eq ComponentTable.id })
        }.orderBy(ComponentTable.id).limit(limit).map { PendingComponent(it[ComponentTable.id], ComponentEmbeddingRepository.version(it)) }
    }

    override fun groups(): List<StoredGroup> = transaction(db) { storedGroups(GroupMembershipTable.selectAll().toList()) }

    override fun snapshot(): GroupSnapshot = transaction(db) {
        val memberships = GroupMembershipTable.selectAll().toList()
        val keys = memberships.map { it[GroupMembershipTable.embeddingKey] }
        val embeddings = ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key inList keys }
            .map { ComponentEmbeddingRepository.decode(it) }.associateBy { it.key.componentId }
        val groups = storedGroups(memberships)
        val outdated = ComponentTable.selectAll().where { ComponentTable.id inList embeddings.keys }.orderBy(ComponentTable.id)
            .map { PendingComponent(it[ComponentTable.id], ComponentEmbeddingRepository.version(it)) }
            .filter { it.version != embeddings.getValue(it.id).key.version }
        check(memberships.size == embeddings.size) { "group embedding missing" }
        GroupSnapshot(groups, embeddings, outdated)
    }

    private fun storedGroups(memberships: List<ResultRow>): List<StoredGroup> {
        val byGroup = memberships.groupBy { it[GroupMembershipTable.groupId] }
        return GroupTable.selectAll().orderBy(GroupTable.id).map { row ->
            StoredGroup(Group(row[GroupTable.id], byGroup[row[GroupTable.id]].orEmpty()
                .map { it[GroupMembershipTable.componentId] }.toSet()), row[GroupTable.revision],
                GroupStorageCodec.vector(row[GroupTable.centroid]))
        }
    }

    override fun apply(change: GroupChange): GroupWriteResult = try {
        transaction(db) { applyAtomically(change) }
    } catch (_: StaleGroupChange) {
        GroupWriteResult.Conflict
    }

    private fun applyAtomically(change: GroupChange): GroupWriteResult {
        val request = GroupStorageCodec.request(change)
        // The first statement is a write: SQLite serializes writers before reading revisions.
        // A rejected change rolls this reservation back together with every other write.
        GroupReceiptTable.insertIgnore {
            it[operationKey] = change.operationKey
            it[GroupReceiptTable.request] = request
            it[groupIds] = ""
        }
        val receipt = GroupReceiptTable.selectAll().where { GroupReceiptTable.operationKey eq change.operationKey }.single()
        require(receipt[GroupReceiptTable.request] == request) { "operation key reused with different content" }
        if (receipt[GroupReceiptTable.groupIds].isNotEmpty()) {
            return GroupWriteResult.AlreadyApplied(GroupStorageCodec.ids(receipt[GroupReceiptTable.groupIds]))
        }
        verifyRevisionAndPartition(change)
        verifyEmbeddings(change)
        val ids = replaceGroups(change)
        GroupReceiptTable.update({ GroupReceiptTable.operationKey eq change.operationKey }) {
            it[groupIds] = GroupStorageCodec.ids(ids)
        }
        return GroupWriteResult.Applied(ids)
    }

    private fun verifyRevisionAndPartition(change: GroupChange) {
        val revisions = GroupTable.selectAll().where { GroupTable.id inList change.expectedGroups.keys }
            .associate { it[GroupTable.id] to it[GroupTable.revision] }
        if (revisions != change.expectedGroups) throw StaleGroupChange()
        val consumed = GroupMembershipTable.selectAll().where { GroupMembershipTable.groupId inList revisions.keys }
            .map { it[GroupMembershipTable.componentId] }.toSet()
        val assignedIncoming = GroupMembershipTable.selectAll().where { GroupMembershipTable.componentId inList change.incomingComponentIds }.any()
        if (assignedIncoming) throw StaleGroupChange()
        require(consumed.intersect(change.incomingComponentIds).isEmpty())
        ComponentPartition(change.resultingGroups.map { it.componentIds }).requireConserves(consumed + change.incomingComponentIds)
        val components = ComponentTable.select(ComponentTable.id).where { ComponentTable.id inList change.embeddings.keys }
            .map { it[ComponentTable.id] }.toSet()
        require(components == change.embeddings.keys) { "component does not exist" }
    }

    private fun verifyEmbeddings(change: GroupChange) {
        val stored = ComponentEmbeddingTable.selectAll().where {
            ComponentEmbeddingTable.key inList change.embeddings.values.map { GroupStorageCodec.key(it.key) }
        }.map { ComponentEmbeddingRepository.decode(it) }.associateBy { it.key.componentId }
        require(stored == change.embeddings) { "expected immutable vectors are missing or changed" }
        val versions = ComponentTable.selectAll().where { ComponentTable.id inList stored.keys }
            .associate { it[ComponentTable.id] to ComponentEmbeddingRepository.version(it) }
        if (stored.values.any { versions[it.key.componentId] != it.key.version }) throw StaleGroupChange()
        val activeKeys = GroupMembershipTable.selectAll().map { it[GroupMembershipTable.embeddingKey] }
        val active = ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key inList activeKeys }
            .map { ComponentEmbeddingRepository.decode(it) }
        val combined = active + stored.values
        if (combined.map { it.key.space }.distinct().size != 1 || combined.map { it.vector.dimension }.distinct().size != 1) {
            throw GroupRebuildRequired("cannot mix group embedding models, formats or dimensions")
        }
        change.resultingGroups.forEach { result ->
            val centroid = GroupGeometry.centroid(result.componentIds.sorted().map { stored.getValue(it).vector })
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
                        it[centroid] = GroupStorageCodec.vector(result.centroid)
                    }
                    target.id
                }
                is GroupTarget.Created -> GroupTable.insert {
                    it[revision] = 1
                    it[centroid] = GroupStorageCodec.vector(result.centroid)
                }[GroupTable.id]
            }
            result.componentIds.sorted().forEach { component ->
                GroupMembershipTable.insert {
                    it[componentId] = component
                    it[groupId] = id
                    it[embeddingKey] = GroupStorageCodec.key(change.embeddings.getValue(component).key)
                }
            }
            id
        }
        GroupTable.deleteWhere { id inList (change.expectedGroups.keys - ids.toSet()) }
        return ids
    }

    private class StaleGroupChange : RuntimeException()
}
