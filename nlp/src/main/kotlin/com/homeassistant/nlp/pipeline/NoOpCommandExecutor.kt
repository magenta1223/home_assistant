package com.homeassistant.nlp.pipeline

import com.homeassistant.core.tools.IToolExecutor
import com.homeassistant.core.commands.UserId
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolResult


class NoOpToolExecutor : IToolExecutor {
    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        ToolResult("Tool '${spec.name.value}' not yet implemented.")
}