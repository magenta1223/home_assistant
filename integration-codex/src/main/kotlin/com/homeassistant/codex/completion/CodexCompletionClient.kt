package com.homeassistant.codex.completion

/** Completes a single structured Codex prompt. */
fun interface CodexCompletionClient {
    /** Runs one completion request and returns the structured response text. */
    suspend fun complete(system: String, userMessage: String, outputSchema: String): String

    suspend fun completeWithImages(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CodexImage>,
    ): String = if (images.isEmpty()) {
        complete(system, userMessage, outputSchema)
    } else {
        error("image completion is unavailable")
    }
}