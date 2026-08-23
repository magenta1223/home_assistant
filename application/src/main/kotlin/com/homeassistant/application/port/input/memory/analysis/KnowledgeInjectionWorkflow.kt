package com.homeassistant.application.port.input.memory.analysis

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.source.SourceDocumentDraft

data class KnowledgeInjectionRequest(
    val identity: ConversationIdentity,
    val source: SourceDocumentDraft,
    val access: MemoryAccess,
)

sealed interface KnowledgeInjectionPreparation {
    data class Ready(
        val requester: RegisteredUser,
        val availableViewers: List<RegisteredUser>,
    ) : KnowledgeInjectionPreparation

    data object RegistrationRequired : KnowledgeInjectionPreparation
    data object Failed : KnowledgeInjectionPreparation
}

class KnowledgeInjectionRegistrationRequiredException internal constructor() :
    RuntimeException("knowledge injection requires a registered user")

class KnowledgeInjectionUnavailableException internal constructor(cause: Throwable) :
    RuntimeException("knowledge injection is unavailable", cause)

/** Resolves a channel identity before delegating knowledge injection to memory analysis. */
interface KnowledgeInjectionWorkflow {
    fun prepare(identity: ConversationIdentity): KnowledgeInjectionPreparation

    suspend fun execute(request: KnowledgeInjectionRequest): MemoryAnalysisResult
}
