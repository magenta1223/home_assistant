package com.homeassistant.application.port.inbound.contexttool

import com.homeassistant.domain.identity.UserId

/** Lets an agent look up stored memory while it answers a user. */
fun interface SearchMemory {
    /** The stored components and episodes [userId] can read that relate to [query], as a reference block. */
    fun search(userId: UserId, query: String): String
}
