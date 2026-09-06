package com.homeassistant.codex.conversation

import kotlinx.serialization.json.Json

internal object CodexJson {
    val parser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

internal val CODEX_JSON: Json
    get() = CodexJson.parser
