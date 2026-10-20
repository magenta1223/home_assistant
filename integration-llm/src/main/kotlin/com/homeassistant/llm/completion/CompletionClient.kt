package com.homeassistant.llm.completion

/** Completes a single structured prompt with the selected agent backend. */
interface CompletionClient {
    /** Runs one completion request with optional [images] and returns the response text that matches [outputSchema]. */
    suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CompletionImage> = emptyList(),
    ): String
}

/** An image attached to a completion; the [fileName] extension identifies its format. */
data class CompletionImage(val fileName: String, val bytes: ByteArray)
