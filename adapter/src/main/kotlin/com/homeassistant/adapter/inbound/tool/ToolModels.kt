package com.homeassistant.adapter.inbound.tool

import kotlinx.serialization.Serializable

/**
 * Tool definition exposed to an LLM or tool registry.
 *
 * @property name Stable tool name used in tool calls.
 * @property description Human-readable description of what the tool does.
 * @property schema JSON-schema-like argument contract for the tool.
 */
@Serializable
data class Tool (
    val name: String,
    val description: String,
    val schema: ToolSchema
)

@JvmInline value class ToolResult(val value: String)
