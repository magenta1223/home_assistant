package com.homeassistant.application.topicanalysis

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidates
import com.homeassistant.core.identity.HouseholdAccessPolicy
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndex
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndexes

object TopicAnalysisFactory {
    fun kakao(
        topicExtractor: TopicExtractor,
        importer: KakaoImporter,
        topicStore: TopicAnalysisStore,
        previewStore: TopicAnalysisPreviewStore,
        searchIndex: TopicClaimSearchIndex = TopicClaimSearchIndexes.unavailable(),
        indexingOutbox: IndexingOutboxStore,
        accessPolicy: HouseholdAccessPolicy,
    ): TopicAnalysisUseCase =
        DefaultTopicAnalysisUseCase(
            analyzeSource = AnalyzeSource(
                topicExtractor = topicExtractor,
                importService = importer,
                previewRepository = previewStore,
                accessPolicy = accessPolicy,
            ),
            saveTopicCandidates = SaveTopicCandidates(
                importService = importer,
                topicRepository = topicStore,
                previewRepository = previewStore,
                topicClaimSearchIndex = searchIndex,
                indexingOutbox = indexingOutbox,
                accessPolicy = accessPolicy,
            ),
        )
}
