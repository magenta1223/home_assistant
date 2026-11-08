package com.homeassistant.application.port.inbound.chatservice

/** Ends the chats of users who have been idle too long. */
fun interface ExpireIdleChats {
    /** Expires every idle session, releases its provider conversation and returns how many expired. */
    fun execute(): Int
}
