package com.homeassistant.domain.component

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserId

/**
 * xMemory component: one long-term piece of knowledge extracted from a single episode. It never changes; a later
 * episode that changes the knowledge adds another Component. Its access is its source episode's current access.
 */
data class Component(
    val id: Int,
    val statement: String,
    val sourceEpisodeId: Int,
    val access: AccessControl,
) {
    init {
        require(id > 0) { "component id must be positive" }
        require(statement.isNotBlank()) { "component statement is required" }
        require(sourceEpisodeId > 0) { "source episode id must be positive" }
    }

    fun isVisibleTo(requester: UserId): Boolean = access.isVisibleTo(requester)
}
