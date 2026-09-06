package com.homeassistant.application.port.output.memory.conversation

/** Creates and ends this application's use of conversation threads. */
interface ConversationThreadLifecycle {
    fun create(): String

    fun end(threadId: String)
}
