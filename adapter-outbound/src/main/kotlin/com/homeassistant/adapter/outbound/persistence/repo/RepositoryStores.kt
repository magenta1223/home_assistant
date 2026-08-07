package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.topicanalysis.save.IndexingOutboxStore
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.topicanalysis.save.TopicCreator
import com.homeassistant.application.memory.io.MemoryReader
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val topicCreator: TopicCreator,
    val canonicalMemories: MemoryReader,
    val indexingOutbox: IndexingOutboxStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
