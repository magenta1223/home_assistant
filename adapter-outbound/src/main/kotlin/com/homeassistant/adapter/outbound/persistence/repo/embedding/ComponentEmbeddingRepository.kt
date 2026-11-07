package com.homeassistant.adapter.outbound.persistence.repo.embedding

import com.homeassistant.adapter.outbound.persistence.repo.group.GroupStorageCodec
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.embedding.contentFingerprint
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentEmbeddingRepository(private val db: Database) : ComponentEmbeddingStore {
    override fun find(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>? = transaction(db) { findStored(key) }

    override fun save(embedding: Embedding<ComponentEmbeddingKey>): Embedding<ComponentEmbeddingKey> = transaction(db) {
        ComponentEmbeddingTable.insertIgnore {
            it[key] = GroupStorageCodec.key(embedding.key)
            it[componentId] = embedding.key.componentId
            it[version] = embedding.key.version
            it[model] = embedding.key.space.model
            it[inputFormat] = embedding.key.space.inputFormat
            it[vector] = GroupStorageCodec.vector(embedding.vector)
        }
        val component = ComponentTable.selectAll().where { ComponentTable.id eq embedding.key.componentId }.singleOrNull()
        require(component != null && version(component) == embedding.key.version) {
            "component missing or vector version changed"
        }
        requireNotNull(findStored(embedding.key))
    }

    private fun findStored(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>? =
        ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key eq GroupStorageCodec.key(key) }
            .singleOrNull()?.let { decode(it) }

    companion object {
        /** The vector version of a Component row: its statement never changes, so the fingerprint identifies the vector. */
        fun version(component: ResultRow): String = contentFingerprint(component[ComponentTable.statement])

        fun decode(row: ResultRow): Embedding<ComponentEmbeddingKey> = Embedding(
            ComponentEmbeddingKey(
                row[ComponentEmbeddingTable.componentId], row[ComponentEmbeddingTable.version],
                EmbeddingSpace(row[ComponentEmbeddingTable.model], row[ComponentEmbeddingTable.inputFormat])
            ),
            GroupStorageCodec.vector(row[ComponentEmbeddingTable.vector]),
        )
    }
}
