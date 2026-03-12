package com.homeassistant.pipeline

import com.homeassistant.constants.ChatResponseType
import com.homeassistant.models.ChatRequest
import com.homeassistant.models.ChatResponse

class DummyChatPipeline : IChatPipeline {
    override suspend fun process(req: ChatRequest): ChatResponse =
        ChatResponse(
            type = ChatResponseType.RESULT.value,
            text = "연결 되었습니다",
            sessionReset = false,
        )
}
