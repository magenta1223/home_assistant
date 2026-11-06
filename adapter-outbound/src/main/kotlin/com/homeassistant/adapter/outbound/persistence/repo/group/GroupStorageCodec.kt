package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupTarget
import com.homeassistant.domain.embedding.EmbeddingVector
import kotlinx.serialization.json.*

/** Stable receipt representation; domain and application remain independent of JSON. */
internal object GroupStorageCodec {
    fun vector(value: EmbeddingVector): String = JsonArray(value.values().map(::JsonPrimitive)).toString()
    fun vector(value: String): EmbeddingVector = EmbeddingVector.restore(Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.double })
    fun ids(values: Collection<Int>): String = JsonArray(values.map(::JsonPrimitive)).toString()
    fun ids(value: String): List<Int> = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.int }

    fun key(value: ComponentEmbeddingKey): String = buildJsonArray {
        add(value.componentId); add(value.version); add(value.space.model); add(value.space.inputFormat)
    }.toString()

    fun request(change: GroupChange): String = buildJsonObject {
        putJsonArray("expected") {
            change.expectedGroups.toSortedMap().forEach { (id, revision) -> add(buildJsonArray { add(id); add(revision) }) }
        }
        put("incoming", Json.parseToJsonElement(ids(change.incomingComponentIds.sorted())))
        putJsonArray("results") {
            change.resultingGroups.forEach { result -> add(buildJsonObject {
                when (val target = result.target) {
                    is GroupTarget.Existing -> put("existing", target.id)
                    is GroupTarget.Created -> put("created", target.key)
                }
                put("components", Json.parseToJsonElement(ids(result.componentIds.sorted())))
                put("centroid", Json.parseToJsonElement(vector(result.centroid)))
            }) }
        }
        putJsonArray("embeddings") {
            change.embeddings.toSortedMap().values.forEach { embedding -> add(buildJsonObject {
                put("key", key(embedding.key)); put("vector", Json.parseToJsonElement(vector(embedding.vector)))
            }) }
        }
    }.toString()
}
