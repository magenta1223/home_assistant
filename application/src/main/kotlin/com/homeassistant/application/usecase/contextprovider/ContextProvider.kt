package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.domain.identity.UserId

/** Supplies one kind of reference a chat answers from, such as memory, as sections in its own priority order. */
fun interface ContextProvider {
    /** What [userId] can read that relates to [question], most relevant first. */
    fun sections(userId: UserId, question: String): List<ContextSection>
}
