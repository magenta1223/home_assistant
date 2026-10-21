package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.DescribeTopic
import com.homeassistant.application.port.input.topic.TopicDescriptionResult
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.topic.TopicDescriptionGenerator
import com.homeassistant.application.port.output.topic.TopicDescriptionStore
import com.homeassistant.application.port.output.topic.TopicReader
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.topic.TopicDescription
import com.homeassistant.domain.topic.TopicDescriptionRefresh
import com.homeassistant.domain.topic.TopicDescriptionRefreshPolicy
import com.homeassistant.domain.topic.TopicView

/**
 * Describes a Topic through the viewer's [TopicView] on demand. The description is written from that view's
 * memories only and cached per view, so viewers who read different slices never share a description.
 */
class TopicDescriber(
    private val topics: TopicReader,
    private val memories: MemoryReader,
    private val descriptions: TopicDescriptionStore,
    private val generator: TopicDescriptionGenerator,
    private val accessPolicy: UserAccessPolicy,
    private val refreshPolicy: TopicDescriptionRefreshPolicy = TopicDescriptionRefreshPolicy(),
) : DescribeTopic {
    override suspend fun describe(userId: UserId, topicId: Int): TopicDescriptionResult {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
        val view = topics.read(listOf(topicId)).singleOrNull()?.viewBy(userId)
        if (view == null || view.isHidden) return TopicDescriptionResult.Hidden
        return when (val refresh = refreshPolicy.decide(descriptions.find(view.key), view)) {
            is TopicDescriptionRefresh.Reuse -> TopicDescriptionResult.Described(refresh.description.text)
            TopicDescriptionRefresh.Generate -> generate(userId, view)
        }
    }

    private suspend fun generate(userId: UserId, view: TopicView): TopicDescriptionResult {
        // The reader re-checks access, so a slice is never trusted on its own.
        val inView = view.memoryIds.toSet()
        val readable = memories.getMemories(userId).filter { it.id in inView }.sortedByDescending { it.createdAt }
        if (readable.isEmpty()) return TopicDescriptionResult.Hidden
        val description = TopicDescription(generator.generate(readable.map { it.content }).trim(), view.versions)
        descriptions.save(view.key, description)
        return TopicDescriptionResult.Described(description.text)
    }
}
