package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.write.MemoryWriter
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore
import com.homeassistant.application.memory.tree.MemoryTreeStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val memoryWriter: MemoryWriter,
    val canonicalMemories: MemoryReader,
    val memoryTree: MemoryTreeStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
