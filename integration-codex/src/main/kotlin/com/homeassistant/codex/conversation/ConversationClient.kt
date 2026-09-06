package com.homeassistant.codex.conversation

/** Manages Codex conversation threads and executes turns on the underlying runtime. */
interface ConversationClient : AutoCloseable {
    /** Verifies that the configured Codex executable can be launched. */
    fun isAvailable(): Boolean

    /** Starts and initializes the long-lived Codex runtime. */
    fun startServer(): Boolean

    fun create(): Result<String>

    fun execute(threadId: String, prompt: String): Result<String>

    fun end(threadId: String)
}
