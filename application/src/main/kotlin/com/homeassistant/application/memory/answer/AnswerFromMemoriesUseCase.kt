package com.homeassistant.application.memory.answer

interface AnswerFromMemoriesUseCase {
    fun answer(request: MemoryAnswerRequest): MemoryAnswerResult
}
