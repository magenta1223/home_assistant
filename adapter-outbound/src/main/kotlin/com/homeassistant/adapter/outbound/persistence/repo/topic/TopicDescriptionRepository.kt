package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.tables.TopicDescriptionTable
import com.homeassistant.application.port.output.topic.TopicDescriptionStore
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.topic.TopicDescription
import com.homeassistant.domain.topic.TopicViewKey
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class TopicDescriptionRepository(private val db: Database) : TopicDescriptionStore {
    override fun find(key: TopicViewKey): TopicDescription? = transaction(db) {
        TopicDescriptionTable.selectAll()
            .where { (TopicDescriptionTable.topicId eq key.topicId) and (TopicDescriptionTable.view eq view(key.slices)) }
            .singleOrNull()
            ?.let { TopicDescription(it[TopicDescriptionTable.description], versions(it[TopicDescriptionTable.sliceVersions])) }
    }

    override fun save(key: TopicViewKey, description: TopicDescription) {
        require(description.versions.keys == key.slices) { "description must cover the view's slices" }
        transaction(db) {
            TopicDescriptionTable.upsert {
                it[topicId] = key.topicId
                it[view] = view(key.slices)
                it[sliceVersions] = encode(description.versions)
                it[TopicDescriptionTable.description] = description.text
            }
        }
    }

    companion object {
        /** Canonical text for a set of slices, independent of iteration order. */
        fun view(slices: Set<MemoryAccess>): String = JsonArray(slices.map(::slice).sorted().map(::JsonPrimitive)).toString()

        private fun encode(versions: Map<MemoryAccess, Int>): String =
            JsonObject(versions.entries.associate { (access, count) -> slice(access) to JsonPrimitive(count) }.toSortedMap()).toString()

        private fun versions(value: String): Map<MemoryAccess, Int> =
            Json.parseToJsonElement(value).jsonObject.entries.associate { (slice, count) -> access(slice) to count.jsonPrimitive.int }

        private fun slice(access: MemoryAccess): String = buildJsonArray {
            add(access.visibility.name)
            access.allowedUserIds.sorted().forEach(::add)
        }.toString()

        private fun access(slice: String): MemoryAccess {
            val parts = Json.parseToJsonElement(slice).jsonArray.map { it.jsonPrimitive.content }
            return MemoryAccess(MemoryVisibility.valueOf(parts.first()), parts.drop(1).toSet())
        }
    }
}
