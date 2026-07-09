package com.homeassistant.domain.topicanalysis

import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate

interface TopicAnalysisStore {
    fun createTopic(candidate: TopicCandidate): Topic
    fun searchApprovedTopics(query: String, limit: Int): List<Topic>
}
