package com.homeassistant.nlp.backend

import com.homeassistant.nlp.backend.codex.CodexCliBackend
import com.homeassistant.core.nlp.LlmBackend

object LmBackendFactory {
    fun create(): LlmBackend = CodexCliBackend()
}
