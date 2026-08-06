package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.identity.UserId

interface TopicAnalysisCommandStore {
    fun createTopic(
        proposal: TopicProposal,
        createdBy: UserId,
        sourceType: String,
        sourceName: String,
    ): Topic
}

interface TopicAnalysisQueryStore {
    fun getApprovedTopics(userId: UserId, topicIds: Collection<Int>): List<Topic>
    fun getTopicsForMemoryIndexing(memoryIds: Collection<Int>): List<Topic>
}

interface TopicAnalysisStore : TopicAnalysisCommandStore, TopicAnalysisQueryStore
