package com.homeassistant.application.usecase.memoryextraction.group

import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationOutcome
import com.homeassistant.application.port.inbound.memoryextraction.ComponentOrganizationResult
import com.homeassistant.application.port.inbound.memoryextraction.GroupOrganizationResult
import com.homeassistant.application.port.inbound.memoryextraction.OrganizePendingComponents
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponentSource
import java.util.concurrent.CancellationException

class OrganizePendingComponentsService(
    private val pending: PendingComponentSource,
    private val organizer: OrganizeComponent,
) : OrganizePendingComponents {
    override fun execute(limit: Int): GroupOrganizationResult {
        require(limit > 0) { "limit must be positive" }
        val results = mutableListOf<ComponentOrganizationResult>()
        for (component in pending.pending(limit)) {
            val outcome = try {
                organizer.execute(component)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rebuild: GroupRebuildRequired) {
                return GroupOrganizationResult.RebuildRequired(results, rebuild.message.orEmpty())
            } catch (failure: Exception) {
                ComponentOrganizationOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
            }
            results.add(ComponentOrganizationResult(component.id, outcome))
        }
        return GroupOrganizationResult.Completed(results)
    }
}
