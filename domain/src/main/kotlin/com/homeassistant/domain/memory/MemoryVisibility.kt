package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId

enum class MemoryVisibility {
    PRIVATE,
    FAMILY;

    fun isVisibleTo(createdBy: UserId, requester: UserId): Boolean =
        this == FAMILY || createdBy == requester
}
