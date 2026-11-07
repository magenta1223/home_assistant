package com.homeassistant.adapter.outbound.persistence.repo.embedding

import com.homeassistant.domain.embedding.EmbeddingVector
import kotlinx.serialization.json.*

/** JSON text of stored vectors, shared by every table that stores one; domain and application remain independent of JSON. */
internal object EmbeddingVectorCodec {
    fun encode(value: EmbeddingVector): String = JsonArray(value.values().map(::JsonPrimitive)).toString()
    fun decode(value: String): EmbeddingVector = EmbeddingVector.restore(Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.double })
}
