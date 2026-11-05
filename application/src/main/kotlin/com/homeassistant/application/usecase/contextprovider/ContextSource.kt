package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.domain.identity.UserId

/** Supplies the episode reference a chat answers from. */
fun interface ContextSource {
    /** The episodes [userId] can read that relate to [question], as a reference block. */
    fun context(userId: UserId, question: String): Context
}