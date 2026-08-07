package com.homeassistant.application.memory.analysis

import com.homeassistant.application.memory.save.MemoryProposalSaver
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.source.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MemoryAnalysisServiceTest {
    @Test
    fun `analyzes and immediately saves flat memories`() = runBlocking {
        val saver = RecordingMemoryProposalSaver()
        val service = MemoryAnalysisService(
            memoryExtractor = FakeMemoryExtractor,
            sourceRecords = FakeSourceRecords,
            memorySaver = saver,
            accessPolicy = AUTHORIZED,
        )

        val result = service.execute(request())

        assertEquals(UserId("dad"), saver.userId)
        assertEquals(listOf("관계"), saver.proposals.map { it.subject })
        assertEquals(listOf("관계"), result.memories.map { it.subject })
    }

    @Test
    fun `rejects unauthorized analysis before importing or saving`() = runBlocking {
        val saver = RecordingMemoryProposalSaver()
        val service = MemoryAnalysisService(
            memoryExtractor = FakeMemoryExtractor,
            sourceRecords = FakeSourceRecords,
            memorySaver = saver,
            accessPolicy = AUTHORIZED,
        )

        assertFailsWith<HouseholdAccessDeniedException> {
            service.execute(request(userId = "stranger"))
        }
        assertEquals(emptyList(), saver.proposals)
    }

    private fun request(userId: String = "dad") = MemoryAnalysisRequest(
        userId = userId,
        source = SourceDocumentDraft(
            source = SourceDescriptor("kakao", "family.txt"),
            records = listOf(SourceRecordDraft("message-1", "오늘 저녁 약속")),
        ),
    )
}

private object FakeMemoryExtractor : MemoryExtractor {
    override suspend fun analyze(document: SourceDocument): List<MemoryProposal> = listOf(
        MemoryProposal(
            content = "오늘 저녁 약속이 있다.",
            subject = "관계",
            memoryType = MemoryType.APPOINTMENT,
            certainty = MemoryCertainty.OBSERVED,
            evidenceIds = listOf(1),
        ),
    )
}

private object FakeSourceRecords : SourceRecordRepository {
    override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>): List<SourceRecord> =
        records.mapIndexed { index, record -> SourceRecord(index + 1, record.deduplicationKey, record.content) }

    override fun findBySource(source: SourceDescriptor): List<SourceRecord> = emptyList()
}

private class RecordingMemoryProposalSaver : MemoryProposalSaver {
    var userId: UserId? = null
    var proposals: List<MemoryProposal> = emptyList()

    override fun save(userId: UserId, proposals: List<MemoryProposal>): List<Memory> {
        this.userId = userId
        this.proposals = proposals
        return emptyList()
    }
}

private val AUTHORIZED = HouseholdAccessPolicy { it == UserId("dad") }
