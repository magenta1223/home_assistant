package com.homeassistant.application.memory.answer

/** Answers a household question using authorized canonical-memory search. */
interface AnswerFromMemoriesUseCase {
    /** Answers a question using memories visible to the requesting user. */
    fun answer(request: MemoryAnswerRequest): MemoryAnswerResult
}
