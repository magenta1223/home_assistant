package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.GroupRebuildRequired
import com.homeassistant.application.port.output.topic.PendingMemory
import com.homeassistant.application.port.output.topic.PendingMemorySource
import com.homeassistant.application.usecase.group.RefreshGroupedTopicEmbeddings
import java.util.concurrent.CancellationException

/** Memory → Topic assignment, then Topic → Group organization (xMemory component → group). */
class OrganizePendingMemoriesService(
    private val pending: PendingMemorySource,
    private val assigner: AssignMemoryToTopics,
    private val groupRefresh: RefreshGroupedTopicEmbeddings,
    private val groups: OrganizePendingTopics,
) : OrganizePendingMemories {
    override fun execute(limit: Int): MemoryOrganizationResult {
        require(limit > 0) { "limit must be positive" }
        val memories = pending.pending(limit).map { MemoryAssignmentResult(it.id, assign(it)) }
        // Joined Topics have revised descriptions, so their Group vectors are refreshed before new Topics are placed.
        val refreshed = try {
            groupRefresh.execute()
        } catch (rebuild: GroupRebuildRequired) {
            return MemoryOrganizationResult(memories, emptyList(),
                GroupOrganizationResult.RebuildRequired(emptyList(), rebuild.message.orEmpty()))
        }
        return MemoryOrganizationResult(memories, refreshed, groups.execute(limit))
    }

    private fun assign(memory: PendingMemory): MemoryAssignmentOutcome = try {
        assigner.execute(memory)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        MemoryAssignmentOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
    }
}
