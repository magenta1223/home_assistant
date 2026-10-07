package com.homeassistant.application.usecase.memory.analysis

import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.topic.placement.*
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.source.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MemoryAnalysisTopicPlacementTest {
    @Test
    fun `topic placement runs after canonical commit and failure preserves successful import`() = runBlocking {
        val events = mutableListOf<String>()
        val source = SourceDescriptor("test", "topic")
        val records = object : SourceRecordRepository {
            override fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>, access: MemoryAccess) =
                SourceRecordSaveResult(
                    listOf(SourceRecord(1, "key", "source", SourceRecordAnalysisStatus.PENDING, access)),
                    emptyList(), 1, 0, 0,
                )
            override fun findBySource(source: SourceDescriptor) = emptyList<SourceRecord>()
        }
        val service = MemoryAnalysisService(
            memoryExtractor = object : com.homeassistant.application.port.output.memory.analysis.MemoryExtractor {
                override suspend fun analyze(document: SourceDocument) = listOf(MemoryProposal("fact", listOf(1)))
            },
            sourceRecords = records,
            memorySaver = MemoryProposalsPersister(CanonicalMemoryBatchWriter { _, _, _ ->
                events += "commit"
                emptyList()
            }),
            accessPolicy = UserAccessPolicy { true },
            topicPlacement = TopicPlacement {
                events += "placement"
                TopicPlacementRunResult.Failed(TopicPlacementFailureStage.PROPOSE)
            },
        )

        val result = service.execute(MemoryAnalysisRequest("user", SourceDocumentDraft(source, listOf(SourceRecordDraft("key", "source")))))

        assertEquals(listOf("commit", "placement"), events)
        assertEquals(1, result.memoryCount)
    }
}
