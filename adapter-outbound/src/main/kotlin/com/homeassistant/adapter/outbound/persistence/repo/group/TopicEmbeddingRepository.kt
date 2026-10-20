package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.tables.TopicEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import com.homeassistant.application.port.output.group.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class TopicEmbeddingRepository(private val db: Database) : TopicEmbeddingStore {
    override fun find(key: TopicEmbeddingKey): TopicEmbedding? = transaction(db) { findStored(key) }

    override fun save(embedding: TopicEmbedding): TopicEmbedding = transaction(db) {
        TopicEmbeddingTable.insertIgnore {
            it[key] = GroupStorageCodec.key(embedding.key)
            it[topicId] = embedding.key.topicId
            it[version] = embedding.key.version
            it[model] = embedding.key.space.model
            it[inputFormat] = embedding.key.space.inputFormat
            it[vector] = GroupStorageCodec.vector(embedding.vector)
        }
        val topic = TopicTable.selectAll().where { TopicTable.id eq embedding.key.topicId }.singleOrNull()
        require(topic != null && version(topic) == embedding.key.version) {
            "topic missing or vector version changed"
        }
        requireNotNull(findStored(embedding.key))
    }

    private fun findStored(key: TopicEmbeddingKey): TopicEmbedding? =
        TopicEmbeddingTable.selectAll().where { TopicEmbeddingTable.key eq GroupStorageCodec.key(key) }
            .singleOrNull()?.let { decode(it) }

    companion object {
        /** The current vector version of a Topic row; memberships are only added, so the count identifies the centroid. */
        fun version(topic: ResultRow): String = topic[TopicTable.memoryCount].toString()

        fun decode(row: ResultRow): TopicEmbedding = TopicEmbedding(
            TopicEmbeddingKey(row[TopicEmbeddingTable.topicId], row[TopicEmbeddingTable.version],
                EmbeddingSpace(row[TopicEmbeddingTable.model], row[TopicEmbeddingTable.inputFormat])),
            GroupStorageCodec.vector(row[TopicEmbeddingTable.vector]),
        )
    }
}
