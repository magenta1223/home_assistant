package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.memory.save.IndexingOutboxStore
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.application.memory.save.MemoryCreator
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore
import com.homeassistant.application.memory.tree.MemoryTreeStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val memoryCreator: MemoryCreator,
    val canonicalMemories: MemoryReader,
    val memoryTree: MemoryTreeStore,
    val indexingOutbox: IndexingOutboxStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
