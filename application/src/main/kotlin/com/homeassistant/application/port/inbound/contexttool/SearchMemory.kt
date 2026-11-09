package com.homeassistant.application.port.inbound.contexttool

/** Lets an agent look up stored memory while it answers, as the user its context token was issued for. */
fun interface SearchMemory {
    /**
     * The stored components and episodes that user can read and that relate to [query], as a reference block.
     *
     * @throws ContextAccessExpiredException when [contextToken] was never issued, was revoked or has expired.
     */
    fun search(contextToken: String, query: String): String
}
