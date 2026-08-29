package com.homeassistant.application.port.output.source

import com.homeassistant.domain.source.SourceReferenceDraft

/** Produces evidence text grounded in a binary PDF or image reference. */
fun interface SourceReferenceInterpreter {
    suspend fun interpret(reference: SourceReferenceDraft): List<SourceReferenceInterpretation>
}

data class SourceReferenceInterpretation(
    /** Stable identifier within the original file, such as `page-3` or `image`. */
    val segmentKey: String,
    val content: String,
) {
    init {
        require(segmentKey.isNotBlank()) { "reference interpretation segmentKey is required" }
        require(content.isNotBlank()) { "reference interpretation content is required" }
    }
}
