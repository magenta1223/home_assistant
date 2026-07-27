package com.homeassistant.repository.repo

import com.homeassistant.repository.db.DatabaseFactory
import com.homeassistant.repository.repo.kakao.KakaoMessageRepository
import com.homeassistant.repository.repo.memory.MemoryRepository
import com.homeassistant.repository.repo.indexing.IndexingOutboxRepository
import com.homeassistant.repository.repo.topicanalysis.TopicAnalysisPreviewRepository
import com.homeassistant.repository.repo.topicanalysis.TopicAnalysisRepository
import com.homeassistant.repository.repo.slackconversation.SlackCodexSessionRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        return RepositoryStores(
            kakaoMessages = KakaoMessageRepository(db),
            kakaoAnalysisPreviews = TopicAnalysisPreviewRepository(db),
            topicAnalysis = TopicAnalysisRepository(db),
            memories = MemoryRepository(db),
            indexingOutbox = IndexingOutboxRepository(db),
            slackCodexSessions = SlackCodexSessionRepository(db),
        )
    }
}
