package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentDescriptionTable
import com.homeassistant.application.port.output.component.ComponentDescriptionStore
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.component.ComponentDescription
import com.homeassistant.domain.component.ComponentViewKey
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal class ComponentDescriptionRepository(private val db: Database) : ComponentDescriptionStore {
    override fun find(key: ComponentViewKey): ComponentDescription? = transaction(db) {
        ComponentDescriptionTable.selectAll()
            .where { (ComponentDescriptionTable.componentId eq key.componentId) and (ComponentDescriptionTable.view eq view(key.slices)) }
            .singleOrNull()
            ?.let { ComponentDescription(it[ComponentDescriptionTable.description], versions(it[ComponentDescriptionTable.sliceVersions])) }
    }

    override fun save(key: ComponentViewKey, description: ComponentDescription) {
        require(description.versions.keys == key.slices) { "description must cover the view's slices" }
        transaction(db) {
            ComponentDescriptionTable.upsert {
                it[componentId] = key.componentId
                it[view] = view(key.slices)
                it[sliceVersions] = encode(description.versions)
                it[ComponentDescriptionTable.description] = description.text
            }
        }
    }

    companion object {
        /** Canonical text for a set of slices, independent of iteration order. */
        fun view(slices: Set<EpisodeAccess>): String = JsonArray(slices.map(::slice).sorted().map(::JsonPrimitive)).toString()

        private fun encode(versions: Map<EpisodeAccess, Int>): String =
            JsonObject(versions.entries.associate { (access, count) -> slice(access) to JsonPrimitive(count) }.toSortedMap()).toString()

        private fun versions(value: String): Map<EpisodeAccess, Int> =
            Json.parseToJsonElement(value).jsonObject.entries.associate { (slice, count) -> access(slice) to count.jsonPrimitive.int }

        private fun slice(access: EpisodeAccess): String = buildJsonArray {
            add(access.visibility.name)
            access.allowedUserIds.sorted().forEach(::add)
        }.toString()

        private fun access(slice: String): EpisodeAccess {
            val parts = Json.parseToJsonElement(slice).jsonArray.map { it.jsonPrimitive.content }
            return EpisodeAccess(EpisodeVisibility.valueOf(parts.first()), parts.drop(1).toSet())
        }
    }
}
