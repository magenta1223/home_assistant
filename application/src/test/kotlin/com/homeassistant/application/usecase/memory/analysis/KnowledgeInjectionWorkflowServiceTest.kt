package com.homeassistant.application.usecase.memory.analysis

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionPreparation
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRegistrationRequiredException
import com.homeassistant.application.port.input.memory.analysis.KnowledgeInjectionRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KnowledgeInjectionWorkflowServiceTest {
    @Test
    fun `registered conversation can prepare and execute memory analysis`() = runBlocking {
        val requester = RegisteredUser(UserId("member-1"), "첫째")
        val viewer = RegisteredUser(UserId("member-2"), "둘째")
        val users = FixedUserRegistry(mapOf(IDENTITY to requester), listOf(requester, viewer))
        val analysis = RecordingMemoryAnalysis()
        val workflow = KnowledgeInjectionWorkflowService(users, analysis)

        val preparation = assertIs<KnowledgeInjectionPreparation.Ready>(workflow.prepare(IDENTITY))
        assertEquals(requester, preparation.requester)
        assertEquals(listOf(requester, viewer), preparation.availableViewers)

        workflow.execute(
            KnowledgeInjectionRequest(
                identity = IDENTITY,
                source = SOURCE,
                access = MemoryAccess.restricted(listOf(viewer.userId)),
            ),
        )

        val request = analysis.requests.single()
        assertEquals("member-1", request.userId)
        assertEquals(SOURCE, request.source)
        assertEquals(setOf("member-2"), request.access.allowedUserIds)
    }

    @Test
    fun `unregistered conversation cannot prepare or execute injection`() {
        val workflow = KnowledgeInjectionWorkflowService(FixedUserRegistry(), RecordingMemoryAnalysis())

        assertEquals(KnowledgeInjectionPreparation.RegistrationRequired, workflow.prepare(IDENTITY))
        assertFailsWith<KnowledgeInjectionRegistrationRequiredException> {
            runBlocking {
                workflow.execute(KnowledgeInjectionRequest(IDENTITY, SOURCE, MemoryAccess.PUBLIC))
            }
        }
    }

    private class FixedUserRegistry(
        private val identities: Map<ConversationIdentity, RegisteredUser> = emptyMap(),
        private val users: List<RegisteredUser> = emptyList(),
    ) : UserRegistry {
        override fun find(identity: ConversationIdentity): RegisteredUser? = identities[identity]

        override fun register(request: RegisterUserRequest): RegisteredUser = error("not used")

        override fun list(): List<RegisteredUser> = users
    }

    private class RecordingMemoryAnalysis : MemoryAnalysis {
        val requests = mutableListOf<MemoryAnalysisRequest>()

        override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
            requests += request
            return MemoryAnalysisResult(
                sourceType = request.source.source.type,
                sourceName = request.source.source.name,
                importedRecordCount = request.source.records.size,
                retriedRecordCount = 0,
                alreadyAnalyzedRecordCount = 0,
                visibility = request.access.visibility,
                allowedUserIds = request.access.allowedUserIds,
                memoryCount = 0,
                memories = emptyList(),
            )
        }
    }

    private companion object {
        val IDENTITY = ConversationIdentity("team-1", "slack-1")
        val SOURCE = SourceDocumentDraft(
            SourceDescriptor("text", "직접 입력"),
            listOf(SourceRecordDraft("key", "content")),
        )
    }
}
