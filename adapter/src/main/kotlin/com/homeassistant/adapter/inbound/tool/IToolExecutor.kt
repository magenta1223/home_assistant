package com.homeassistant.adapter.inbound.tool

import com.homeassistant.domain.identity.UserId

interface IToolExecutor {
    suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult
}
