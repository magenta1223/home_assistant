package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.identity.HouseholdAccessScope

interface TopicAnalysisCommandStore {
    fun createTopic(candidate: TopicCandidate): Topic
}

interface TopicAnalysisQueryStore {
    fun searchApprovedTopics(scope: HouseholdAccessScope, query: String, limit: Int): List<Topic>
    fun getApprovedTopics(scope: HouseholdAccessScope, topicIds: Collection<Int>): List<Topic>
    fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic>
}

interface TopicAnalysisStore : TopicAnalysisCommandStore, TopicAnalysisQueryStore
