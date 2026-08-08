package com.homeassistant.application.memory.memorygroundedchat

import com.homeassistant.application.memory.read.SearchMemoriesRequest

class MemoryGroundedChatbot(
    private val answerContext: MemoryAnswerContextProvider,
) {
    fun answer(request: MemoryAnswerRequest): MemoryAnswerResult {
        val result = answerContext.context(
            SearchMemoriesRequest(request.userId, request.question, request.limit),
        )
        val answer = if (result.matches.isEmpty()) {
            "저장된 기억에서 관련 내용을 찾지 못했습니다."
        } else {
            "저장된 기억 기준으로는 ${result.matches.first().content}"
        }
        return MemoryAnswerResult(result.query, answer, result.matches)
    }
}
