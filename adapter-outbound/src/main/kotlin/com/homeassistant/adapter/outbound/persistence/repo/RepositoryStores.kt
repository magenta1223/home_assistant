package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.output.source.SourceRecordRepository
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.port.output.group.*

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val canonicalMemoryBatchWriter: CanonicalMemoryBatchWriter,
    val canonicalMemories: MemoryReader,
    val topicMaintenance: TopicMaintenanceStore,
    val pendingTopics: PendingTopicSource,
    val topicEmbeddings: TopicEmbeddingStore,
    val groupSnapshots: GroupSnapshotReader,
    val groupChanges: GroupChangeWriter,
    val topicAssignmentOutbox: TopicAssignmentOutbox,
    val memoryEmbeddings: MemoryEmbeddingStore,
    val memoryConversationSessions: MemoryConversationSessionStore,
    val users: UserStore,
)
