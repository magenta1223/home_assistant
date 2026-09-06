package com.homeassistant.codex.conversation

import kotlinx.serialization.json.Json

internal object CodexJson {
    val parser: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }
}

internal val CODEX_JSON: Json
    get() = CodexJson.parser
