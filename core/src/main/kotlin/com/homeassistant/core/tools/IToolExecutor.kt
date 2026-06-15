package com.homeassistant.core.tools

import com.homeassistant.core.identity.UserId

interface IToolExecutor {
    suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult
}
