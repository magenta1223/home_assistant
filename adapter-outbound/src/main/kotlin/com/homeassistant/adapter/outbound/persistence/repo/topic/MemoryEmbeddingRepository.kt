package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupStorageCodec
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.descriptionFingerprint
import com.homeassistant.application.port.output.topic.*
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class MemoryEmbeddingRepository(private val db: Database) : MemoryEmbeddingStore {
    override fun find(key: MemoryEmbeddingKey): MemoryEmbedding? = transaction(db) { findStored(key) }

    override fun save(embedding: MemoryEmbedding): MemoryEmbedding = transaction(db) {
        MemoryEmbeddingTable.insertIgnore {
            it[key] = key(embedding.key)
            it[memoryId] = embedding.key.memoryId
            it[contentHash] = embedding.key.contentHash
            it[model] = embedding.key.space.model
            it[inputFormat] = embedding.key.space.inputFormat
            it[vector] = GroupStorageCodec.vector(embedding.vector)
        }
        val memory = MemoryTable.selectAll().where { MemoryTable.id eq embedding.key.memoryId }.singleOrNull()
        require(memory != null && descriptionFingerprint(memory[MemoryTable.content]) == embedding.key.contentHash) {
            "memory missing or content changed"
        }
        requireNotNull(findStored(embedding.key))
    }

    private fun findStored(key: MemoryEmbeddingKey): MemoryEmbedding? =
        MemoryEmbeddingTable.selectAll().where { MemoryEmbeddingTable.key eq key(key) }.singleOrNull()?.let { decode(it) }

    companion object {
        fun key(value: MemoryEmbeddingKey): String = buildJsonArray {
            add(value.memoryId); add(value.contentHash); add(value.space.model); add(value.space.inputFormat)
        }.toString()

        fun decode(row: ResultRow): MemoryEmbedding = MemoryEmbedding(
            MemoryEmbeddingKey(row[MemoryEmbeddingTable.memoryId], row[MemoryEmbeddingTable.contentHash],
                EmbeddingSpace(row[MemoryEmbeddingTable.model], row[MemoryEmbeddingTable.inputFormat])),
            GroupStorageCodec.vector(row[MemoryEmbeddingTable.vector]),
        )
    }
}
