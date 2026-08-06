package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.topicanalysis.save.IndexingOutboxStore
import com.homeassistant.domain.source.SourceRecordStore
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore

data class RepositoryStores(
    val sourceRecords: SourceRecordStore,
    val topicAnalysisReviews: TopicAnalysisReviewStore,
    val topicAnalysis: TopicAnalysisStore,
    val indexingOutbox: IndexingOutboxStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
