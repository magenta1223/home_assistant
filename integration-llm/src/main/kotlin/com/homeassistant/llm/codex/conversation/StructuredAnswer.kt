package com.homeassistant.llm.codex.conversation

import kotlinx.serialization.Serializable

@Serializable
internal data class StructuredAnswer(val answer: String)
