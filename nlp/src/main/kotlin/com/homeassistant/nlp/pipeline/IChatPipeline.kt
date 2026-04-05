package com.homeassistant.nlp.pipeline

import com.homeassistant.core.models.ChatRequest
import com.homeassistant.core.models.ChatResponse

interface IChatPipeline {
    suspend fun process(req: ChatRequest): ChatResponse
}
