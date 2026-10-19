package com.homeassistant.llm.completion

/** Completes a single structured prompt with the selected agent backend. */
fun interface CompletionClient {
    /** Runs one completion request and returns the response text that matches [outputSchema]. */
    suspend fun complete(system: String, userMessage: String, outputSchema: String): String

    suspend fun completeWithImages(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CompletionImage>,
    ): String = if (images.isEmpty()) {
        complete(system, userMessage, outputSchema)
    } else {
        error("image completion is unavailable")
    }
}

/** An image attached to a completion; the [fileName] extension identifies its format. */
data class CompletionImage(val fileName: String, val bytes: ByteArray)
