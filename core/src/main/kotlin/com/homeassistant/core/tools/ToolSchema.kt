package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: PropertySchema? = null,
)