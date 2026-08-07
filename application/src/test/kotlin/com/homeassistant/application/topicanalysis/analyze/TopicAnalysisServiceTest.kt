package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.application.topicanalysis.save.TopicProposalSaver
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TopicAnalysisServiceTest {
    @Test
    fun `analyzes and immediately saves extracted proposals`() = runBlocking {
        val saver = RecordingTopicProposalSaver()
        val service = TopicAnalysisService(
            topicExtractor = FakeTopicExtractor,
            sourceRecords = FakeSourceRecords,
            topicSaver = saver,
            accessPolicy = AUTHORIZED,
        )

        val result = service.execute(request())

        assertEquals(UserId("dad"), saver.userId)
        assertEquals(SourceDescriptor("kakao", "family.txt"), saver.source)
        assertEquals(listOf("관계 표현"), saver.proposals.map { it.title })
        assertEquals(listOf("관계 표현"), result.topics.map { it.title })
    }

    @Test
    fun `rejects unauthorized analysis before importing or saving`() = runBlocking {
        val saver = RecordingTopicProposalSaver()
        val service = TopicAnalysisService(
            topicExtractor = FakeTopicExtractor,
            sourceRecords = FakeSourceRecords,
            topicSaver = saver,
            accessPolicy = AUTHORIZED,
        )

        assertFailsWith<HouseholdAccessDeniedException> {
            service.execute(request(userId = "stranger"))
        }
        assertEquals(emptyList(), saver.proposals)
    }

    private fun request(userId: String = "dad") =
        TopicAnalysisRequest(
            userId = userId,
            source = SourceDocumentDraft(
                source = SourceDescriptor("kakao", "family.txt"),
                records = listOf(SourceRecordDraft("message-1", "오늘 저녁 약속")),
            ),
        )
}

private object FakeTopicExtractor : TopicExtractor {
    override suspend fun analyze(document: SourceDocument): List<TopicProposal> = listOf(proposal())
}

private object FakeSourceRecords : SourceRecordRepository {
    override fun saveAll(
        source: SourceDescriptor,
        records: List<SourceRecordDraft>,
    ): List<SourceRecord> = records.mapIndexed { index, record ->
        SourceRecord(index + 1, record.deduplicationKey, record.content)
    }

    override fun findBySource(source: SourceDescriptor): List<SourceRecord> = emptyList()
}

private class RecordingTopicProposalSaver : TopicProposalSaver {
    var userId: UserId? = null
    var source: SourceDescriptor? = null
    var proposals: List<TopicProposal> = emptyList()

    override fun save(
        userId: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): List<Topic> {
        this.userId = userId
        this.source = source
        this.proposals = proposals
        return emptyList()
    }
}

private fun proposal() =
    TopicProposal(
        title = "관계 표현",
        summary = "약속을 정했다.",
        categories = listOf("family"),
        memories = listOf(
            MemoryProposal(
                content = "오늘 저녁 약속이 있다.",
                subject = "가족",
                memoryType = MemoryType.APPOINTMENT,
                certainty = MemoryCertainty.OBSERVED,
                evidenceIds = listOf(1),
            ),
        ),
    )

private val AUTHORIZED = HouseholdAccessPolicy { it == UserId("dad") }
