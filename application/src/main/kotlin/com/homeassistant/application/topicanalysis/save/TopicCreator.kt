package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicProposal

fun interface TopicCreator {
    fun create(proposal: TopicProposal, createdBy: UserId, source: SourceDescriptor): Topic
}
