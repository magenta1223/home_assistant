package com.homeassistant.llm.conversation

/** Holds multi-turn conversations with the selected agent backend. Conversation ids are chosen by the backend. */
interface ConversationClient : AutoCloseable {
    /** Starts a conversation and returns its id. */
    fun begin(): Result<String>

    /** Sends one [prompt] to [conversationId] and returns the answer; a failure's message names its category. */
    fun send(conversationId: String, prompt: String): Result<String>

    /** Releases what this process holds for [conversationId]. */
    fun end(conversationId: String)
}
