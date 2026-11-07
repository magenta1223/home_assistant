package com.homeassistant.adapter.outbound.persistence.repo.group

import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingKey
import com.homeassistant.domain.embedding.EmbeddingVector
import kotlinx.serialization.json.*

/** JSON text of stored vectors and embedding keys; domain and application remain independent of JSON. */
internal object GroupStorageCodec {
    fun vector(value: EmbeddingVector): String = JsonArray(value.values().map(::JsonPrimitive)).toString()
    fun vector(value: String): EmbeddingVector = EmbeddingVector.restore(Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.double })

    fun key(value: ComponentEmbeddingKey): String = buildJsonArray {
        add(value.componentId); add(value.version); add(value.space.model); add(value.space.inputFormat)
    }.toString()
}
