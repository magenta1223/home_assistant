package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate

interface TopicAnalysisStore {
    fun createTopic(candidate: TopicCandidate): Topic
    fun searchApprovedTopics(scope: HouseholdAccessScope, query: String, limit: Int): List<Topic>
    fun getApprovedTopics(scope: HouseholdAccessScope, topicIds: Collection<Int>): List<Topic>
    fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic>
}
