package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolCallSpec(val name: String, val arguments: String)

