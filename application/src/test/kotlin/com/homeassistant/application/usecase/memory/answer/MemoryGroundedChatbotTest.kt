package com.homeassistant.application.usecase.memory.answer

import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerUnavailableException
import com.homeassistant.application.port.input.memory.search.MemorySearch
import com.homeassistant.application.port.input.memory.search.MemorySearchUnavailableException
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MemoryGroundedChatbotTest {
    @Test
    fun `translates search failures to the memory answer failure contract`() {
        val failure = IllegalStateException("search unavailable")
        val search = MemorySearch { throw MemorySearchUnavailableException(failure) }
        val context = MemoryAnswerContextProvider(
            memorySearcher = search,
            memories = object : MemoryReader {
                override fun getMemories(userId: UserId): List<Memory> = emptyList()
            },
            semanticSearcher = SemanticMemoryIndexSearcher { _, _ -> emptyList() },
        )

        val unavailable = assertFailsWith<MemoryAnswerUnavailableException> {
            MemoryGroundedChatbot(context).answer(
                MemoryAnswerRequest(
                    userId = "member-1",
                    question = "question",
                ),
            )
        }

        assertSame(failure, unavailable.cause?.cause)
    }
}
