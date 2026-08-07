package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId

enum class MemoryVisibility {
    PRIVATE,
    FAMILY,
    /** Internal tree node; never returned as user-visible memory content. */
    STRUCTURAL;

    fun isVisibleTo(createdBy: UserId, requester: UserId): Boolean =
        when (this) {
            FAMILY -> true
            PRIVATE -> createdBy == requester
            STRUCTURAL -> true
        }
}
