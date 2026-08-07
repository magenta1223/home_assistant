package com.homeassistant.application.memory.answer

import com.homeassistant.application.memory.io.MemorySearchMatch
import com.homeassistant.application.memory.io.SearchMemoriesResult
import com.homeassistant.application.memory.io.SearchMemoriesUseCase
import kotlin.test.Test
import kotlin.test.assertEquals

class AnswerFromMemoriesTest {
    @Test
    fun `formats the first searched memory as an answer`() {
        val answer = AnswerFromMemories(
            SearchMemoriesUseCase { request ->
                SearchMemoriesResult(
                    request.query.trim(),
                    listOf(MemorySearchMatch(1, "독립 기억", listOf(10))),
                )
            },
        )

        val result = answer.answer(MemoryAnswerRequest("dad", " 무엇? ", 5))

        assertEquals("저장된 기억 기준으로는 독립 기억", result.answer)
        assertEquals("무엇?", result.question)
    }
}
