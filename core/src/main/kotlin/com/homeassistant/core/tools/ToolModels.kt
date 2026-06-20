package com.homeassistant.core.tools

import kotlinx.serialization.Serializable


@Serializable
data class Tool (
    val name: String,
    val description: String,
    val schema: ToolSchema
)

@JvmInline value class ToolResult(val value: String)
