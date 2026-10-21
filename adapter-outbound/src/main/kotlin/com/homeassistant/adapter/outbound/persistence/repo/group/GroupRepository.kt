package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.application.port.output.group.*
import com.homeassistant.domain.group.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

internal class GroupRepository(private val db: Database) : PendingTopicSource, GroupReader, GroupSnapshotReader, GroupChangeWriter {
    override fun pending(limit: Int): List<PendingTopic> = transaction(db) {
        require(limit > 0)
        TopicTable.selectAll().where {
            notExists(GroupMembershipTable.selectAll().where { GroupMembershipTable.topicId eq TopicTable.id })
        }.orderBy(TopicTable.id).limit(limit).map { PendingTopic(it[TopicTable.id], TopicEmbeddingRepository.version(it)) }
    }

    override fun groups(): List<StoredGroup> = transaction(db) { storedGroups(GroupMembershipTable.selectAll().toList()) }

    override fun snapshot(): GroupSnapshot = transaction(db) {
        val memberships = GroupMembershipTable.selectAll().toList()
        val keys = memberships.map { it[GroupMembershipTable.embeddingKey] }
        val embeddings = TopicEmbeddingTable.selectAll().where { TopicEmbeddingTable.key inList keys }
            .map { TopicEmbeddingRepository.decode(it) }.associateBy { it.key.topicId }
        val groups = storedGroups(memberships)
        val outdated = TopicTable.selectAll().where { TopicTable.id inList embeddings.keys }.orderBy(TopicTable.id)
            .map { PendingTopic(it[TopicTable.id], TopicEmbeddingRepository.version(it)) }
            .filter { it.version != embeddings.getValue(it.id).key.version }
        check(memberships.size == embeddings.size) { "group embedding missing" }
        GroupSnapshot(groups, embeddings, outdated)
    }

    private fun storedGroups(memberships: List<ResultRow>): List<StoredGroup> {
        val byGroup = memberships.groupBy { it[GroupMembershipTable.groupId] }
        return GroupTable.selectAll().orderBy(GroupTable.id).map { row ->
            StoredGroup(Group(row[GroupTable.id], byGroup[row[GroupTable.id]].orEmpty()
                .map { it[GroupMembershipTable.topicId] }.toSet()), row[GroupTable.revision],
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
            .map { it[GroupMembershipTable.topicId] }.toSet()
        val assignedIncoming = GroupMembershipTable.selectAll().where { GroupMembershipTable.topicId inList change.incomingTopicIds }.any()
        if (assignedIncoming) throw StaleGroupChange()
        require(consumed.intersect(change.incomingTopicIds).isEmpty())
        TopicPartition(change.resultingGroups.map { it.topicIds }).requireConserves(consumed + change.incomingTopicIds)
        val topics = TopicTable.select(TopicTable.id).where { TopicTable.id inList change.embeddings.keys }
            .map { it[TopicTable.id] }.toSet()
        require(topics == change.embeddings.keys) { "topic does not exist" }
    }

    private fun verifyEmbeddings(change: GroupChange) {
        val stored = TopicEmbeddingTable.selectAll().where {
            TopicEmbeddingTable.key inList change.embeddings.values.map { GroupStorageCodec.key(it.key) }
        }.map { TopicEmbeddingRepository.decode(it) }.associateBy { it.key.topicId }
        require(stored == change.embeddings) { "expected immutable vectors are missing or changed" }
        val versions = TopicTable.selectAll().where { TopicTable.id inList stored.keys }
            .associate { it[TopicTable.id] to TopicEmbeddingRepository.version(it) }
        if (stored.values.any { versions[it.key.topicId] != it.key.version }) throw StaleGroupChange()
        val activeKeys = GroupMembershipTable.selectAll().map { it[GroupMembershipTable.embeddingKey] }
        val active = TopicEmbeddingTable.selectAll().where { TopicEmbeddingTable.key inList activeKeys }
            .map { TopicEmbeddingRepository.decode(it) }
        val combined = active + stored.values
        if (combined.map { it.key.space }.distinct().size != 1 || combined.map { it.vector.dimension }.distinct().size != 1) {
            throw GroupRebuildRequired("cannot mix group embedding models, formats or dimensions")
        }
        change.resultingGroups.forEach { result ->
            val centroid = GroupGeometry.centroid(result.topicIds.sorted().map { stored.getValue(it).vector })
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
            result.topicIds.sorted().forEach { topic ->
                GroupMembershipTable.insert {
                    it[topicId] = topic
                    it[groupId] = id
                    it[embeddingKey] = GroupStorageCodec.key(change.embeddings.getValue(topic).key)
                }
            }
            id
        }
        GroupTable.deleteWhere { id inList (change.expectedGroups.keys - ids.toSet()) }
        return ids
    }

    private class StaleGroupChange : RuntimeException()
}
