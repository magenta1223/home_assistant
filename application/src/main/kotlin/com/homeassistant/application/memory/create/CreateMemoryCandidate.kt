package com.homeassistant.application.memory.create

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryCommandStore

data class CreateMemoryCandidateInput(
    val userId: UserId,
    val conversationId: String,
    val domainName: String,
    val memoryType: MemoryType,
    val content: String,
    val summary: String,
    val confidence: Double,
    val subjectMemberId: String? = null,
    val sourceConversationMessageId: Int? = null,
)

data class CreateMemoryCandidateOutput(val candidateId: Int)

class CreateMemoryCandidate(
    private val memoryStore: MemoryCommandStore,
) {
    fun execute(input: CreateMemoryCandidateInput): CreateMemoryCandidateOutput =
        CreateMemoryCandidateOutput(
            memoryStore.createCandidate(
                userId = input.userId,
                conversationId = input.conversationId,
                domainName = input.domainName,
                memoryType = input.memoryType,
                content = input.content,
                summary = input.summary,
                confidence = input.confidence,
                sourceConversationMessageId = input.sourceConversationMessageId,
                subjectMemberId = input.subjectMemberId,
            ),
        )
}
