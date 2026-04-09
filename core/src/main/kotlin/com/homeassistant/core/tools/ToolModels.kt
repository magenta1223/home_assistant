package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ToolName(val value: String)

@Serializable
@JvmInline
value class ToolDescription(val value: String)

@Serializable
@JvmInline
value class ToolArguments(val value: String)  // JSON string

@Serializable
data class Tool (
    val name: ToolName,
    val description: ToolDescription,
    val schema: ToolSchema
)

@JvmInline value class ToolResult(val value: String)
