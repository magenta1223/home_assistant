package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepository
import com.homeassistant.adapter.outbound.persistence.repo.indexing.IndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicAnalysisPreviewRepository
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicAnalysisRepository
import com.homeassistant.adapter.outbound.persistence.repo.slackconversation.SlackCodexSessionRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        return RepositoryStores(
            sourceRecords = SourceRecordRepository(db),
            topicAnalysisPreviews = TopicAnalysisPreviewRepository(db),
            topicAnalysis = TopicAnalysisRepository(db),
            indexingOutbox = IndexingOutboxRepository(db),
            slackCodexSessions = SlackCodexSessionRepository(db),
        )
    }
}
