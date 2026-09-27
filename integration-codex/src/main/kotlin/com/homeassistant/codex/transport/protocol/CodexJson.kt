package com.homeassistant.codex.transport.protocol

import kotlinx.serialization.json.Json

internal val CODEX_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}
