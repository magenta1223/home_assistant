package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.GroupRebuildRequired
import com.homeassistant.application.port.output.topic.PendingMemorySource
import com.homeassistant.application.port.output.topic.TopicRebuildRequired
import com.homeassistant.application.usecase.group.RefreshGroupedTopicEmbeddings
import java.util.concurrent.CancellationException

/** Runs the two xMemory organization levels in order: Memory → Topic, then Topic → Group. */
class OrganizePendingMemoriesService(
    private val pending: PendingMemorySource,
    private val organizer: OrganizeMemory,
    private val descriptions: DescribeOrganizedTopics,
    private val groupRefresh: RefreshGroupedTopicEmbeddings,
    private val groups: OrganizePendingTopics,
) : OrganizePendingMemories {
    override fun execute(limit: Int): MemoryOrganizationResult {
        require(limit > 0) { "limit must be positive" }
        val memories = mutableListOf<MemoryOrganizationEntry>()
        for (memory in pending.pending(limit)) {
            val outcome = try {
                organizer.execute(memory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rebuild: TopicRebuildRequired) {
                return MemoryOrganizationResult.RebuildRequired(memories, rebuild.message.orEmpty())
            } catch (failure: Exception) {
                MemoryOrganizationOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
            }
            memories.add(MemoryOrganizationEntry(memory.id, outcome))
        }
        val described = descriptions.execute(limit)
        val refreshed = try {
            groupRefresh.execute()
        } catch (rebuild: GroupRebuildRequired) {
            return MemoryOrganizationResult.Completed(memories, described, emptyList(),
                GroupOrganizationResult.RebuildRequired(emptyList(), rebuild.message.orEmpty()))
        }
        return MemoryOrganizationResult.Completed(memories, described, refreshed, groups.execute(limit))
    }
}
