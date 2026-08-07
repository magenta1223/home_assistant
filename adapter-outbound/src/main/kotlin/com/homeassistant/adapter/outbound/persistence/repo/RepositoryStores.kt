package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.topicanalysis.save.IndexingOutboxStore
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.topicanalysis.save.TopicCreator
import com.homeassistant.application.memory.index.MemoryIndexingSource
import com.homeassistant.application.memory.search.CanonicalMemoryReader
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val topicCreator: TopicCreator,
    val canonicalMemories: CanonicalMemoryReader,
    val memoryIndexingSource: MemoryIndexingSource,
    val indexingOutbox: IndexingOutboxStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
