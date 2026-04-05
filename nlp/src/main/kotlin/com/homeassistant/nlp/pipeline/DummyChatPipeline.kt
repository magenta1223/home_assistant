package com.homeassistant.nlp.pipeline

import com.homeassistant.core.models.ChatRequest
import com.homeassistant.core.models.ChatResponse
import com.homeassistant.core.nlp.ChatResponseType

class DummyChatPipeline : IChatPipeline {
    override suspend fun process(req: ChatRequest): ChatResponse =
        ChatResponse(
            type = ChatResponseType.RESULT.value,
            text = "연결 되었습니다",
            sessionReset = false,
        )
}
