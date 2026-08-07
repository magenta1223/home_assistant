package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicProposal

/** Creates a persisted topic from an approved topic proposal. */
fun interface TopicCreator {
    /** Creates and returns a topic from an approved proposal. */
    fun create(proposal: TopicProposal, createdBy: UserId, source: SourceDescriptor): Topic
}
