package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

/**
 * JSON-schema-like object schema for a tool's argument payload.
 *
 * @property type Top-level JSON type for the payload.
 * @property properties Named argument schemas accepted by the tool.
 * @property required Property names that must be present in a valid call.
 */
@Serializable
data class ToolSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
)

/**
 * JSON-schema-like schema for one tool argument property.
 *
 * @property type JSON type name expected for the property.
 * @property description Human-readable description of the property.
 * @property enum Optional list of accepted string values.
 * @property items Optional item schema when the property is an array.
 */
@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: PropertySchema? = null,
)
