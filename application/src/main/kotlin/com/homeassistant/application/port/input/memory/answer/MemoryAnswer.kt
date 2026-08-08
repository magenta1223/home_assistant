package com.homeassistant.application.port.input.memory.answer

/** Answers a household question from the caller's visible canonical memories. */
fun interface MemoryAnswer {
    fun answer(request: MemoryAnswerRequest): MemoryAnswerResult
}

class MemoryAnswerUnavailableException internal constructor(
    cause: Throwable,
) : RuntimeException("memory answer is unavailable", cause)
