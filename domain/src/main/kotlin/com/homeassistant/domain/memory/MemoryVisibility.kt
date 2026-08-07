package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId

enum class MemoryVisibility {
    PRIVATE,
    PUBLIC
    ;

    fun isVisibleTo(createdBy: UserId, requester: UserId): Boolean =
        when (this) {
            PUBLIC -> true
            PRIVATE -> createdBy == requester
        }
}
