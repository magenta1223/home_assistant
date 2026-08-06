package com.homeassistant.application.memory.answer

import com.homeassistant.application.memory.search.SearchMemoriesRequest
import com.homeassistant.application.memory.search.SearchMemoriesUseCase

class AnswerFromMemories(
    private val searchMemories: SearchMemoriesUseCase,
) : AnswerFromMemoriesUseCase {
    override fun answer(request: MemoryAnswerRequest): MemoryAnswerResult {
        val result = searchMemories.search(
            SearchMemoriesRequest(request.userId, request.question, request.limit),
        )
        val answer = if (result.matches.isEmpty()) {
            "승인된 기억에서 관련 내용을 찾지 못했습니다."
        } else {
            "저장된 기억 기준으로는 ${result.matches.first().content}"
        }
        return MemoryAnswerResult(result.query, answer, result.matches)
    }
}
