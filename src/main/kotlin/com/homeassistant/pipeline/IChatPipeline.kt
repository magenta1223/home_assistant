package com.homeassistant.pipeline

import com.homeassistant.models.ChatRequest
import com.homeassistant.models.ChatResponse

interface IChatPipeline {
    suspend fun process(req: ChatRequest): ChatResponse
}
