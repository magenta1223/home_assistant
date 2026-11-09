package com.homeassistant.application.port.inbound.contexttool

import com.homeassistant.domain.identity.UserId

/** Tells the context tool server whose context a request may read, from the context token the agent presents. */
fun interface ContextAccess {
    /** The user [contextToken] was issued for, or null when it was never issued, was revoked or has expired. */
    fun ownerOf(contextToken: String): UserId?
}
