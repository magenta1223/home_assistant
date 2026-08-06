package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.topicanalysis.save.IndexingOutboxStore
import com.homeassistant.domain.source.SourceRecordStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.slackconversation.SlackCodexSessionStore

data class RepositoryStores(
    val sourceRecords: SourceRecordStore,
    val topicAnalysisPreviews: TopicAnalysisPreviewStore,
    val topicAnalysis: TopicAnalysisStore,
    val indexingOutbox: IndexingOutboxStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
