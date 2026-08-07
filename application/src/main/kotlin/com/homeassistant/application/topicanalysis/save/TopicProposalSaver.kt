package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicProposal

/** Saves analyzed topic proposals as canonical topics and indexes their memories. */
fun interface TopicProposalSaver {
    fun save(
        userId: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): List<Topic>
}
