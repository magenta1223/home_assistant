package com.homeassistant.application.usecase.memory.analysis

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionPreparation
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRegistrationRequiredException
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRequest
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionUnavailableException
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionWorkflow
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import org.slf4j.LoggerFactory

class KnowledgeInjectionWorkflowService(
    private val users: UserRegistry,
    private val memoryAnalysis: MemoryAnalysis,
) : KnowledgeInjectionWorkflow {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun prepare(identity: ConversationIdentity): KnowledgeInjectionPreparation = try {
        val requester = users.find(identity)
            ?: return KnowledgeInjectionPreparation.RegistrationRequired
        KnowledgeInjectionPreparation.Ready(
            requester = requester,
            availableViewers = users.list(),
        )
    } catch (error: Exception) {
        log.warn("Knowledge injection preparation failed category={}", error.javaClass.simpleName)
        KnowledgeInjectionPreparation.Failed
    }

    override suspend fun execute(request: KnowledgeInjectionRequest): MemoryAnalysisResult {
        val requester = try {
            users.find(request.identity)
        } catch (error: Exception) {
            throw KnowledgeInjectionUnavailableException(error)
        }
            ?: throw KnowledgeInjectionRegistrationRequiredException()
        return memoryAnalysis.execute(
            MemoryAnalysisRequest(
                userId = requester.userId.value,
                source = request.source,
                access = request.access,
            ),
        )
    }
}
