package com.homeassistant.common.json

import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
object JsonSerializer {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        explicitNulls = false
        encodeDefaults = true
    }

    inline fun <reified T> T.encodeToString(): String {
        return json.encodeToString(this)
    }

    inline fun <reified T> String.decodeFromString(): T {
        return json.decodeFromString(this)
    }

    fun String.parseToJsonElement(): JsonElement {
        return json.parseToJsonElement(this)
    }

    fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Char -> JsonPrimitive(this.toString())
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> buildJsonObject {
            this@toJsonElement.forEach { (key, value) ->
                require(key is String) { "JSON object keys must be strings" }
                put(key, value.toJsonElement())
            }
        }
        is Iterable<*> -> buildJsonArray {
            this@toJsonElement.forEach { add(it.toJsonElement()) }
        }
        is Array<*> -> buildJsonArray {
            this@toJsonElement.forEach { add(it.toJsonElement()) }
        }
        else -> error("Unsupported JSON value: ${this::class.qualifiedName}")
    }
}
