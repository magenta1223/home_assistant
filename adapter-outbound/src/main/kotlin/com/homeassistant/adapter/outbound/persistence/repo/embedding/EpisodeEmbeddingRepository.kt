package com.homeassistant.adapter.outbound.persistence.repo.embedding

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupStorageCodec
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.embedding.contentFingerprint
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.embedding.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.embedding.EpisodeEmbeddingStore
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class EpisodeEmbeddingRepository(private val db: Database) : EpisodeEmbeddingStore {
    override fun find(key: EpisodeEmbeddingKey): Embedding<EpisodeEmbeddingKey>? = transaction(db) { findStored(key) }

    override fun save(embedding: Embedding<EpisodeEmbeddingKey>): Embedding<EpisodeEmbeddingKey> = transaction(db) {
        EpisodeEmbeddingTable.insertIgnore {
            it[key] = key(embedding.key)
            it[episodeId] = embedding.key.episodeId
            it[contentHash] = embedding.key.contentHash
            it[model] = embedding.key.space.model
            it[inputFormat] = embedding.key.space.inputFormat
            it[vector] = GroupStorageCodec.vector(embedding.vector)
        }
        val episode = EpisodeTable.selectAll().where { EpisodeTable.id eq embedding.key.episodeId }.singleOrNull()
        require(episode != null && contentFingerprint(episode[EpisodeTable.content]) == embedding.key.contentHash) {
            "episode missing or content changed"
        }
        requireNotNull(findStored(embedding.key))
    }

    private fun findStored(key: EpisodeEmbeddingKey): Embedding<EpisodeEmbeddingKey>? =
        EpisodeEmbeddingTable.selectAll().where { EpisodeEmbeddingTable.key eq key(key) }.singleOrNull()?.let { decode(it) }

    companion object {
        fun key(value: EpisodeEmbeddingKey): String = buildJsonArray {
            add(value.episodeId); add(value.contentHash); add(value.space.model); add(value.space.inputFormat)
        }.toString()

        fun decode(row: ResultRow): Embedding<EpisodeEmbeddingKey> = Embedding(
            EpisodeEmbeddingKey(
                row[EpisodeEmbeddingTable.episodeId], row[EpisodeEmbeddingTable.contentHash],
                EmbeddingSpace(row[EpisodeEmbeddingTable.model], row[EpisodeEmbeddingTable.inputFormat])
            ),
            GroupStorageCodec.vector(row[EpisodeEmbeddingTable.vector]),
        )
    }
}
