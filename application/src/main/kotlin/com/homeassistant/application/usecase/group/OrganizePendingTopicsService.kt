package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.input.group.*
import com.homeassistant.application.port.output.group.GroupRebuildRequired
import com.homeassistant.application.port.output.group.PendingTopicSource
import java.util.concurrent.CancellationException

class OrganizePendingTopicsService(
    private val pending: PendingTopicSource,
    private val organizer: OrganizeTopic,
) : OrganizePendingTopics {
    override fun execute(limit: Int): GroupOrganizationResult {
        require(limit > 0) { "limit must be positive" }
        val results = mutableListOf<TopicOrganizationResult>()
        for (topic in pending.pending(limit)) {
            val outcome = try {
                organizer.execute(topic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rebuild: GroupRebuildRequired) {
                return GroupOrganizationResult.RebuildRequired(results, rebuild.message.orEmpty())
            } catch (failure: Exception) {
                TopicOrganizationOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
            }
            results.add(TopicOrganizationResult(topic.id, outcome))
        }
        return GroupOrganizationResult.Completed(results)
    }
}
