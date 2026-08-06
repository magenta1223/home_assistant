package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.domain.kakao.KakaoMessageStore
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.slackconversation.SlackCodexSessionStore

data class RepositoryStores(
    val kakaoMessages: KakaoMessageStore,
    val kakaoAnalysisPreviews: TopicAnalysisPreviewStore,
    val topicAnalysis: TopicAnalysisStore,
    val indexingOutbox: IndexingOutboxStore,
    val slackCodexSessions: SlackCodexSessionStore,
)
