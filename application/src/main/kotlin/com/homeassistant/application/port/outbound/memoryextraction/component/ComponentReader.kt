package com.homeassistant.application.port.outbound.memoryextraction.component

import com.homeassistant.domain.component.Component

/**
 * Components of every access. A user-facing caller must keep only the Components the requester can read before
 * anything leaves the use case.
 */
interface ComponentReader {
    /** Every Component, oldest first. */
    fun all(): List<Component>

    fun read(componentIds: Collection<Int>): List<Component>
}