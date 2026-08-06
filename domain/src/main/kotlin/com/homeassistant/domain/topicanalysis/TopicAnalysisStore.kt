package com.homeassistant.domain.topicanalysis

import com.homeassistant.domain.identity.UserId

interface TopicAnalysisCommandStore {
    fun createTopic(proposal: ProposedTopic): Topic
}

interface TopicAnalysisQueryStore {
    fun searchApprovedTopics(userId: UserId, query: String, limit: Int): List<Topic>
    fun getApprovedTopics(userId: UserId, topicIds: Collection<Int>): List<Topic>
    fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic>
}

interface TopicAnalysisStore : TopicAnalysisCommandStore, TopicAnalysisQueryStore
