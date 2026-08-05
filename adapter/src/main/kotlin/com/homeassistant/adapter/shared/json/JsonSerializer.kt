package com.homeassistant.adapter.shared.json

import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
}
