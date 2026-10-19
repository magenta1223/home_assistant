package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupStorageCodec
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.group.GroupGeometry
import com.homeassistant.domain.group.TopicPartition
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

internal class TopicOrganizationRepository(private val db: Database) :
    PendingMemorySource, TopicOrganizationSnapshotReader, TopicOrganizationWriter, PendingTopicDescriptionSource {

    override fun pending(limit: Int): List<PendingMemory> = transaction(db) {
        require(limit > 0)
        MemoryTable.selectAll().where {
            notExists(TopicOrganizedMemoryTable.selectAll().where { TopicOrganizedMemoryTable.memoryId eq MemoryTable.id })
        }.orderBy(MemoryTable.id).limit(limit).map { PendingMemory(it[MemoryTable.id], it[MemoryTable.content]) }
    }

    override fun snapshot(): TopicOrganizationSnapshot = transaction(db) {
        val organized = TopicOrganizedMemoryTable.selectAll().toList()
        val embeddings = MemoryEmbeddingTable.selectAll()
            .where { MemoryEmbeddingTable.key inList organized.map { it[TopicOrganizedMemoryTable.embeddingKey] } }
            .map { MemoryEmbeddingRepository.decode(it) }.associateBy { it.key.memoryId }
        check(organized.size == embeddings.size) { "organized memory embedding missing" }
        val byTopic = organized.groupBy({ it[TopicOrganizedMemoryTable.topicId] }, { it[TopicOrganizedMemoryTable.memoryId] })
        val topics = TopicOrganizationTable.selectAll().orderBy(TopicOrganizationTable.topicId).map { row ->
            val id = row[TopicOrganizationTable.topicId]
            OrganizedTopic(id, byTopic.getValue(id).toSet(), row[TopicOrganizationTable.revision],
                GroupStorageCodec.vector(row[TopicOrganizationTable.centroid]))
        }
        TopicOrganizationSnapshot(topics, embeddings)
    }

    override fun apply(change: TopicOrganizationChange): TopicOrganizationWriteResult = try {
        transaction(db) {
            val consumed = verifyRevisionAndPartition(change)
            verifyEmbeddings(change)
            TopicOrganizationWriteResult.Applied(replaceTopics(change, consumed))
        }
    } catch (_: StaleTopicChange) {
        TopicOrganizationWriteResult.Conflict
    }

    override fun pendingDescriptions(limit: Int): List<TopicDescriptionRequest> = transaction(db) {
        require(limit > 0)
        TopicOrganizationTable.selectAll()
            .where { TopicOrganizationTable.describedRevision neq TopicOrganizationTable.revision }
            .orderBy(TopicOrganizationTable.topicId).limit(limit).map { row ->
                val id = row[TopicOrganizationTable.topicId]
                val contents = (TopicOrganizedMemoryTable innerJoin MemoryTable).select(MemoryTable.content)
                    .where { TopicOrganizedMemoryTable.topicId eq id }.orderBy(MemoryTable.id)
                    .map { it[MemoryTable.content] }
                TopicDescriptionRequest(id, row[TopicOrganizationTable.revision], contents)
            }
    }

    override fun describe(topicId: Int, revision: Long, description: String): TopicDescriptionWriteResult = transaction(db) {
        require(description.isNotBlank())
        val current = TopicOrganizationTable.update({
            (TopicOrganizationTable.topicId eq topicId) and (TopicOrganizationTable.revision eq revision)
        }) { it[describedRevision] = revision }
        if (current == 0) return@transaction TopicDescriptionWriteResult.Conflict
        TopicTable.update({ TopicTable.id eq topicId }) { it[TopicTable.description] = description }
        TopicDescriptionWriteResult.Applied
    }

    /** Returns the organized memories of the expected topics, which the change must redistribute. */
    private fun verifyRevisionAndPartition(change: TopicOrganizationChange): Set<Int> {
        val revisions = TopicOrganizationTable.selectAll().where { TopicOrganizationTable.topicId inList change.expectedTopics.keys }
            .associate { it[TopicOrganizationTable.topicId] to it[TopicOrganizationTable.revision] }
        if (revisions != change.expectedTopics) throw StaleTopicChange()
        val incomingOrganized = TopicOrganizedMemoryTable.selectAll()
            .where { TopicOrganizedMemoryTable.memoryId inList change.incomingMemoryIds }.any()
        if (incomingOrganized) throw StaleTopicChange()
        val consumed = TopicOrganizedMemoryTable.selectAll().where { TopicOrganizedMemoryTable.topicId inList revisions.keys }
            .map { it[TopicOrganizedMemoryTable.memoryId] }.toSet()
        TopicPartition(change.resultingTopics.map { it.memoryIds }).requireConserves(consumed + change.incomingMemoryIds)
        val memories = MemoryTable.select(MemoryTable.id).where { MemoryTable.id inList change.embeddings.keys }
            .map { it[MemoryTable.id] }.toSet()
        require(memories == change.embeddings.keys) { "memory does not exist" }
        return consumed
    }

    private fun verifyEmbeddings(change: TopicOrganizationChange) {
        val stored = MemoryEmbeddingTable.selectAll().where {
            MemoryEmbeddingTable.key inList change.embeddings.values.map { MemoryEmbeddingRepository.key(it.key) }
        }.map { MemoryEmbeddingRepository.decode(it) }.associateBy { it.key.memoryId }
        require(stored == change.embeddings) { "expected immutable vectors are missing or changed" }
        val activeKeys = TopicOrganizedMemoryTable.selectAll().map { it[TopicOrganizedMemoryTable.embeddingKey] }
        val active = MemoryEmbeddingTable.selectAll().where { MemoryEmbeddingTable.key inList activeKeys }
            .map { MemoryEmbeddingRepository.decode(it) }
        val combined = active + stored.values
        if (combined.map { it.key.space }.distinct().size != 1 || combined.map { it.vector.dimension }.distinct().size != 1) {
            throw TopicRebuildRequired("cannot mix topic memory embedding models, formats or dimensions")
        }
        change.resultingTopics.forEach { result ->
            val centroid = GroupGeometry.centroid(result.memoryIds.sorted().map { stored.getValue(it).vector })
            require(centroid == result.centroid) { "centroid does not match membership vectors" }
        }
    }

    private fun replaceTopics(change: TopicOrganizationChange, consumed: Set<Int>): List<Int> {
        val expected = change.expectedTopics.keys
        TopicOrganizedMemoryTable.deleteWhere { topicId inList expected }
        TopicMembershipTable.deleteWhere {
            (topicId inList expected) and (category eq ORGANIZED_TOPIC_CATEGORY) and (memoryId inList consumed)
        }
        return change.resultingTopics.map { result ->
            val id = when (val target = result.target) {
                is TopicTarget.Existing -> {
                    TopicOrganizationTable.update({ TopicOrganizationTable.topicId eq target.id }) {
                        it[revision] = change.expectedTopics.getValue(target.id) + 1
                        it[centroid] = GroupStorageCodec.vector(result.centroid)
                    }
                    target.id
                }
                TopicTarget.Created -> createTopic(result)
            }
            result.memoryIds.sorted().forEach { memory ->
                TopicOrganizedMemoryTable.insert {
                    it[memoryId] = memory
                    it[topicId] = id
                    it[embeddingKey] = MemoryEmbeddingRepository.key(change.embeddings.getValue(memory).key)
                }
                TopicMembershipTable.insertIgnore {
                    it[topicId] = id
                    it[category] = ORGANIZED_TOPIC_CATEGORY
                    it[memoryId] = memory
                }
            }
            id
        }
    }

    /** The smallest memory's content describes a new Topic until its summary is generated. */
    private fun createTopic(result: ResultingTopic): Int {
        val provisional = MemoryTable.select(MemoryTable.content).where { MemoryTable.id eq result.memoryIds.min() }
            .single()[MemoryTable.content]
        val id = TopicTable.insert {
            it[creationKey] = "organization:${UUID.randomUUID()}"
            it[description] = provisional
        }[TopicTable.id]
        TopicOrganizationTable.insert {
            it[topicId] = id
            it[revision] = 1
            it[describedRevision] = 0
            it[centroid] = GroupStorageCodec.vector(result.centroid)
        }
        return id
    }

    private class StaleTopicChange : RuntimeException()
}
