package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.domain.identity.UserId

/** Supplies the reference a chat answers from. */
fun interface ContextSource {
    /** What [userId] can read that relates to [question], as a reference block. */
    fun context(userId: UserId, question: String): Context
}