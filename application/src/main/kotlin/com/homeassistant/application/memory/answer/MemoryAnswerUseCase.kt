package com.homeassistant.application.memory.answer

interface MemoryAnswerUseCase {
    fun answer(request: MemoryAnswerRequest): MemoryAnswerResult
}
