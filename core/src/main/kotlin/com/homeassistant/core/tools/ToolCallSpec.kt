package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

/**
 * Parsed tool call emitted by an LLM backend.
 *
 * @property name Tool name requested by the model.
 * @property arguments Raw JSON argument payload for the tool.
 */
@Serializable
data class ToolCallSpec(val name: String, val arguments: String)

