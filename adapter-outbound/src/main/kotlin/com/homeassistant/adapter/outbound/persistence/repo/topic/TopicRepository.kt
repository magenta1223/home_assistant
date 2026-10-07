package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicRelationTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicRelation
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class TopicRepository(private val db: Database) : TopicMaintenanceStore, TopicPlacementStore {
    override fun loadUnplaced(limit: Int): List<TopicPlacementMemory> = transaction(db) {
        require(limit > 0) { "limit must be positive" }
        MemoryTable.select(MemoryTable.id, MemoryTable.content).where {
            notExists(TopicMembershipTable.select(TopicMembershipTable.memoryId).where {
                TopicMembershipTable.memoryId eq MemoryTable.id
            })
        }.orderBy(MemoryTable.id).limit(limit).map { TopicPlacementMemory(it[MemoryTable.id], it[MemoryTable.content]) }
    }

    override fun loadCandidates(limit: Int): List<TopicCandidate> = transaction(db) {
        require(limit > 0) { "limit must be positive" }
        TopicTable.selectAll().orderBy(TopicTable.id, SortOrder.DESC).limit(limit)
            .map { TopicCandidate(it[TopicTable.id], it[TopicTable.description]) }
    }

    override fun commitIfUnplaced(memoryIds: Set<Int>, batch: TopicWriteBatch): TopicPlacementCommitResult = transaction(db) {
        require(memoryIds.isNotEmpty()) { "placement scope must not be empty" }
        require(batch.memberships.flatMap { it.memoryIds }.toSet() == memoryIds) {
            "placement must assign all and only scoped memories"
        }
        val existingIds = MemoryTable.select(MemoryTable.id).where { MemoryTable.id inList memoryIds }
            .map { it[MemoryTable.id] }.toSet()
        require(existingIds == memoryIds) { "placement memory does not exist" }
        val placedIds = TopicMembershipTable.select(TopicMembershipTable.memoryId)
            .where { TopicMembershipTable.memoryId inList memoryIds }.map { it[TopicMembershipTable.memoryId] }.toSet()
        when {
            placedIds == memoryIds -> TopicPlacementCommitResult.AlreadyPlaced
            placedIds.isNotEmpty() -> TopicPlacementCommitResult.Stale
            else -> TopicPlacementCommitResult.Placed(applyBatch(batch))
        }
    }

    override fun readForMaintenance(topicIds: Collection<Int>): List<Topic> = transaction(db) {
        if (topicIds.isEmpty()) return@transaction emptyList()
        val memberships = TopicMembershipTable.selectAll()
            .where { TopicMembershipTable.topicId inList topicIds }
            .orderBy(TopicMembershipTable.memoryId)
            .groupBy { it[TopicMembershipTable.topicId] }
        TopicTable.selectAll().where { TopicTable.id inList topicIds }.orderBy(TopicTable.id).map { row ->
            val id = row[TopicTable.id]
            Topic(id, row[TopicTable.description], memberships[id].orEmpty().groupBy(
                keySelector = { it[TopicMembershipTable.category] },
                valueTransform = { it[TopicMembershipTable.memoryId] },
            ))
        }
    }

    override fun relationsForMaintenance(topicIds: Collection<Int>): Set<TopicRelation> = transaction(db) {
        if (topicIds.isEmpty()) return@transaction emptySet()
        TopicRelationTable.selectAll().where {
            (TopicRelationTable.firstTopicId inList topicIds) or (TopicRelationTable.secondTopicId inList topicIds)
        }.mapTo(linkedSetOf()) { TopicRelation(it[TopicRelationTable.firstTopicId], it[TopicRelationTable.secondTopicId]) }
    }

    override fun apply(batch: TopicWriteBatch): TopicWriteResult = transaction(db) { applyBatch(batch) }

    private fun applyBatch(batch: TopicWriteBatch): TopicWriteResult {
        val createdIds = createTopics(batch.creations)
        val memberships = resolveMemberships(batch.memberships, createdIds)
        val relations = resolveRelations(batch.relations, createdIds)
        verifyTargets(memberships, relations)
        addMemberships(memberships)
        addRelations(relations)
        return TopicWriteResult(createdIds)
    }

    private fun createTopics(creations: List<TopicCreation>): Map<String, Int> = creations.associate { creation ->
        val existing = TopicTable.selectAll().where { TopicTable.creationKey eq creation.creationKey }.singleOrNull()
        val id = if (existing != null) {
            require(existing[TopicTable.description] == creation.description) { "topic creation key conflicts with its existing description" }
            existing[TopicTable.id]
        } else {
            TopicTable.insert {
                it[creationKey] = creation.creationKey
                it[description] = creation.description
            }[TopicTable.id]
        }
        creation.creationKey to id
    }

    private fun resolveMemberships(additions: List<TopicMembershipAddition>, createdIds: Map<String, Int>): List<Membership> =
        additions.flatMap { addition ->
            val id = resolve(addition.topic, createdIds)
            addition.memoryIds.map { Membership(id, addition.category, it) }
        }.distinct()

    private fun resolveRelations(additions: List<TopicRelationAddition>, createdIds: Map<String, Int>): Set<TopicRelation> =
        additions.mapTo(linkedSetOf()) { TopicRelation.between(resolve(it.first, createdIds), resolve(it.second, createdIds)) }

    private fun resolve(reference: TopicReference, createdIds: Map<String, Int>): Int = when (reference) {
        is TopicReference.Existing -> reference.topicId
        is TopicReference.Created -> requireNotNull(createdIds[reference.creationKey]) { "topic creation reference is not declared in this batch" }
    }

    private fun verifyTargets(memberships: List<Membership>, relations: Set<TopicRelation>) {
        val topicIds = memberships.map { it.topicId }.toSet() + relations.flatMap { listOf(it.firstTopicId, it.secondTopicId) }
        if (topicIds.isNotEmpty()) {
            val existing = TopicTable.select(TopicTable.id).where { TopicTable.id inList topicIds }.map { it[TopicTable.id] }.toSet()
            require(existing == topicIds) { "topic reference does not exist" }
        }
        val memoryIds = memberships.map { it.memoryId }.toSet()
        if (memoryIds.isNotEmpty()) {
            val existing = MemoryTable.select(MemoryTable.id).where { MemoryTable.id inList memoryIds }.map { it[MemoryTable.id] }.toSet()
            require(existing == memoryIds) { "memory reference does not exist" }
        }
    }

    private fun addMemberships(memberships: List<Membership>) {
        memberships.forEach { membership ->
            TopicMembershipTable.insertIgnore {
                it[topicId] = membership.topicId
                it[category] = membership.category
                it[memoryId] = membership.memoryId
            }
        }
    }

    private fun addRelations(relations: Set<TopicRelation>) {
        relations.forEach { relation ->
            TopicRelationTable.insertIgnore {
                it[firstTopicId] = relation.firstTopicId
                it[secondTopicId] = relation.secondTopicId
            }
        }
    }

    private data class Membership(val topicId: Int, val category: String, val memoryId: Int)
}
