package com.homeassistant.codex.conversation

@JvmInline
value class CodexThreadId(val value: String) {
    init {
        require(value.isNotBlank()) { "Codex thread id is required" }
    }
}

/** Owns one initialized Codex app-server and its conversation threads. */
interface CodexAppServer : AutoCloseable {
    fun createThread(): Result<CodexThreadId>

    fun executeTurn(
        threadId: CodexThreadId,
        prompt: String,
    ): Result<String>

    fun releaseThread(threadId: CodexThreadId)
}
