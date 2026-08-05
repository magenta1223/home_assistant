package com.homeassistant.adapter.inbound.tool

interface ToolGroup {
    val tools: List<Tool>
    fun execute(spec: ToolCallSpec): ToolResult
}
