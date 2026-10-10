package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.write.MemoryIndexingOutbox
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.application.port.output.memory.answer.PendingRegistrationQuestionStore
import com.homeassistant.application.port.output.topic.TopicMaintenanceStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val canonicalMemoryBatchWriter: CanonicalMemoryBatchWriter,
    val memoryIndexingOutbox: MemoryIndexingOutbox,
    val canonicalMemories: MemoryReader,
    val topicMaintenance: TopicMaintenanceStore,
    val memoryConversationSessions: MemoryConversationSessionStore,
    val users: UserStore,
    val pendingRegistrationQuestions: PendingRegistrationQuestionStore,
)
