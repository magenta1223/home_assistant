package com.homeassistant.core.tools

interface ToolGroup {
    val tools: List<Tool>
    fun execute(spec: ToolCallSpec): ToolResult
}
