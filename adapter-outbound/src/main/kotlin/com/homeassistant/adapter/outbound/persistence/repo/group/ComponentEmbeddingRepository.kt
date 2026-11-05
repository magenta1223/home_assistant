package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.descriptionFingerprint
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentEmbeddingRepository(private val db: Database) : ComponentEmbeddingStore {
    override fun find(key: ComponentEmbeddingKey): ComponentEmbedding? = transaction(db) { findStored(key) }

    override fun save(embedding: ComponentEmbedding): ComponentEmbedding = transaction(db) {
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

    private fun findStored(key: ComponentEmbeddingKey): ComponentEmbedding? =
        ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key eq GroupStorageCodec.key(key) }
            .singleOrNull()?.let { decode(it) }

    companion object {
        /** The vector version of a Component row: its statement never changes, so the fingerprint identifies the vector. */
        fun version(component: ResultRow): String = descriptionFingerprint(component[ComponentTable.statement])

        fun decode(row: ResultRow): ComponentEmbedding = ComponentEmbedding(
            ComponentEmbeddingKey(
                row[ComponentEmbeddingTable.componentId], row[ComponentEmbeddingTable.version],
                EmbeddingSpace(row[ComponentEmbeddingTable.model], row[ComponentEmbeddingTable.inputFormat])
            ),
            GroupStorageCodec.vector(row[ComponentEmbeddingTable.vector]),
        )
    }
}
