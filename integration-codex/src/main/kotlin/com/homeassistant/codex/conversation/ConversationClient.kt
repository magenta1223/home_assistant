package com.homeassistant.codex.conversation

/** Runs Codex conversation turns and owns the underlying runtime lifecycle. */
interface ConversationClient : AutoCloseable {
    /** Verifies that the configured Codex executable can be launched. */
    fun isAvailable(): Boolean

    /** Starts and initializes the long-lived Codex runtime. */
    fun startServer(): Boolean

    fun start(prompt: String, onThreadStarted: (String) -> Unit): Result<String>

    fun resume(threadId: String, prompt: String): Result<String>

    fun end(threadId: String)
}
