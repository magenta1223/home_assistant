package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.TopicDescriptionOutcome
import com.homeassistant.application.port.output.topic.*
import java.util.concurrent.CancellationException

/**
 * Generates the xMemory theme summary for organized Topics whose membership changed since their last
 * description. Pending work is durable, so an interrupted run is resumed by the next call.
 */
class DescribeOrganizedTopics(
    private val pending: PendingTopicDescriptionSource,
    private val generator: TopicDescriptionGenerator,
    private val writer: TopicOrganizationWriter,
) {
    fun execute(limit: Int): List<TopicDescriptionOutcome> = pending.pendingDescriptions(limit).map { describe(it) }

    private fun describe(request: TopicDescriptionRequest): TopicDescriptionOutcome {
        val description = try {
            generator.describe(request.memoryContents).trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return TopicDescriptionOutcome.Failed(request.topicId, failure.message ?: failure.javaClass.simpleName)
        }
        if (description.isBlank()) return TopicDescriptionOutcome.Failed(request.topicId, "blank topic description")
        return when (writer.describe(request.topicId, request.revision, description)) {
            TopicDescriptionWriteResult.Applied -> TopicDescriptionOutcome.Described(request.topicId)
            TopicDescriptionWriteResult.Conflict -> TopicDescriptionOutcome.Superseded(request.topicId)
        }
    }
}
