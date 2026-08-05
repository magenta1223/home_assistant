package com.homeassistant.application.topicanalysis

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.application.topicanalysis.analyze.SourceTextParser
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidates
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.kakao.KakaoImporter
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import com.homeassistant.application.topicanswer.answer.TopicClaimSearchIndex
import com.homeassistant.application.topicanswer.answer.TopicClaimSearchIndexes

object TopicAnalysisFactory {
    fun kakao(
        topicExtractor: TopicExtractor,
        sourceTextParser: SourceTextParser,
        importer: KakaoImporter,
        topicStore: TopicAnalysisStore,
        previewStore: TopicAnalysisPreviewStore,
        searchIndex: TopicClaimSearchIndex = TopicClaimSearchIndexes.unavailable(),
        indexingOutbox: IndexingOutboxStore,
        accessPolicy: HouseholdAccessPolicy,
    ): TopicAnalysisUseCases =
        TopicAnalysisUseCases(
            analyzeSource = AnalyzeSource(
                topicExtractor = topicExtractor,
                sourceTextParser = sourceTextParser,
                importService = importer,
                previewRepository = previewStore,
                accessPolicy = accessPolicy,
            ),
            saveTopicCandidates = SaveTopicCandidates(
                importService = importer,
                sourceTextParser = sourceTextParser,
                topicRepository = topicStore,
                previewRepository = previewStore,
                topicClaimSearchIndex = searchIndex,
                indexingOutbox = indexingOutbox,
                accessPolicy = accessPolicy,
            ),
        )
}
