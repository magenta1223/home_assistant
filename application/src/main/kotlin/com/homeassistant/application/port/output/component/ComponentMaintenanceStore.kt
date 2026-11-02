package com.homeassistant.application.port.output.component

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

/** Trusted ingestion/maintenance writes. This port does not grant end users mutation access. */
interface ComponentMaintenanceStore : ComponentReader {
    /**
     * Marks [episodeId] as extracted and stores [statements] as its Components in one write; returns their ids in order.
     * An episode with no long-term knowledge is still marked, so it is never extracted again.
     */
    fun saveExtraction(episodeId: Int, statements: List<String>): List<Int>
}
