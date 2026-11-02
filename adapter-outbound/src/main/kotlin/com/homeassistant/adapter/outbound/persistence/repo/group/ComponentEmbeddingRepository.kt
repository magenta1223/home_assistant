package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.application.port.output.group.*
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
        /** The current vector version of a Component row; memberships are only added, so the count identifies the centroid. */
        fun version(component: ResultRow): String = component[ComponentTable.episodeCount].toString()

        fun decode(row: ResultRow): ComponentEmbedding = ComponentEmbedding(
            ComponentEmbeddingKey(row[ComponentEmbeddingTable.componentId], row[ComponentEmbeddingTable.version],
                EmbeddingSpace(row[ComponentEmbeddingTable.model], row[ComponentEmbeddingTable.inputFormat])),
            GroupStorageCodec.vector(row[ComponentEmbeddingTable.vector]),
        )
    }
}
