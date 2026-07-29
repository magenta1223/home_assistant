package com.homeassistant.nlp.topicanalysis.api

import com.homeassistant.core.identity.HouseholdAccessPolicy
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndex
import com.homeassistant.nlp.topicanalysis.impl.KakaoMessageTopicAnalysisService

object TopicAnalysisFactory {
    fun kakao(
        backend: LlmBackend,
        importer: KakaoImporter,
        topicStore: TopicAnalysisStore,
        previewStore: TopicAnalysisPreviewStore,
        searchIndex: TopicClaimSearchIndex,
        indexingOutbox: IndexingOutboxStore,
        accessPolicy: HouseholdAccessPolicy,
    ): TopicAnalysisUseCase =
        KakaoMessageTopicAnalysisService(
            backend = backend,
            importService = importer,
            topicRepository = topicStore,
            previewRepository = previewStore,
            topicClaimSearchIndex = searchIndex,
            indexingOutbox = indexingOutbox,
            accessPolicy = accessPolicy,
        )
}
