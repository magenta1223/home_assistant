package com.homeassistant.core.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolCallSpec(val name: ToolName, val arguments: ToolArguments)

