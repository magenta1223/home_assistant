package com.homeassistant.adapter.outbound.persistence.repo.embedding

import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentEmbeddingTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.embedding.contentFingerprint
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentEmbeddingRepository(private val db: Database) : ComponentEmbeddingStore {
    override fun find(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>? = transaction(db) { findStored(key) }

    override fun save(embedding: Embedding<ComponentEmbeddingKey>): Embedding<ComponentEmbeddingKey> = transaction(db) {
        ComponentEmbeddingTable.insertIgnore {
            it[key] = key(embedding.key)
            it[componentId] = embedding.key.componentId
            it[version] = embedding.key.version
            it[model] = embedding.key.space.model
            it[inputFormat] = embedding.key.space.inputFormat
            it[vector] = EmbeddingVectorCodec.encode(embedding.vector)
        }
        val component = ComponentTable.selectAll().where { ComponentTable.id eq embedding.key.componentId }.singleOrNull()
        require(component != null && version(component) == embedding.key.version) {
            "component missing or vector version changed"
        }
        requireNotNull(findStored(embedding.key))
    }

    private fun findStored(key: ComponentEmbeddingKey): Embedding<ComponentEmbeddingKey>? =
        ComponentEmbeddingTable.selectAll().where { ComponentEmbeddingTable.key eq key(key) }
            .singleOrNull()?.let { decode(it) }

    companion object {
        /** The stored text of [value], which identifies its vector row and the Group memberships that use it. */
        fun key(value: ComponentEmbeddingKey): String = buildJsonArray {
            add(value.componentId); add(value.version); add(value.space.model); add(value.space.inputFormat)
        }.toString()

        /** The vector version of a Component row: its statement never changes, so the fingerprint identifies the vector. */
        fun version(component: ResultRow): String = contentFingerprint(component[ComponentTable.statement])

        fun decode(row: ResultRow): Embedding<ComponentEmbeddingKey> = Embedding(
            ComponentEmbeddingKey(
                row[ComponentEmbeddingTable.componentId], row[ComponentEmbeddingTable.version],
                EmbeddingSpace(row[ComponentEmbeddingTable.model], row[ComponentEmbeddingTable.inputFormat])
            ),
            EmbeddingVectorCodec.decode(row[ComponentEmbeddingTable.vector]),
        )
    }
}
