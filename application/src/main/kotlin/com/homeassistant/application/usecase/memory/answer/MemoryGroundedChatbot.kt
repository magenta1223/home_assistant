package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.input.memory.answer.MemoryAnswer
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerResult
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerUnavailableException
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.domain.identity.HouseholdAccessDeniedException

class MemoryGroundedChatbot(
    private val answerContext: MemoryAnswerContextProvider,
) : MemoryAnswer {
    override fun answer(request: MemoryAnswerRequest): MemoryAnswerResult {
        val searchRequest = SearchMemoriesRequest(request.userId, request.question, request.limit)
        try {
            return answerFromVisibleMemories(searchRequest)
        } catch (error: HouseholdAccessDeniedException) {
            throw error
        } catch (error: Exception) {
            throw MemoryAnswerUnavailableException(error)
        }
    }

    private fun answerFromVisibleMemories(request: SearchMemoriesRequest): MemoryAnswerResult {
        val result = answerContext.context(request)
        val answer = if (result.directMatches.isEmpty()) {
            "저장된 기억에서 관련 내용을 찾지 못했습니다."
        } else {
            "저장된 기억 기준으로는 ${result.directMatches.first().content}"
        }
        return MemoryAnswerResult(result.query, answer, result.directMatches)
    }
}
