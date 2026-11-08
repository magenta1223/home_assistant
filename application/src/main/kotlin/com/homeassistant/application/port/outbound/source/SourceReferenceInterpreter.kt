package com.homeassistant.application.port.outbound.source

import com.homeassistant.domain.source.SourceReferenceDraft

/** Produces evidence text grounded in a binary PDF or image reference. */
fun interface SourceReferenceInterpreter {
    /** The interpreted segments of [reference] in file order, or why the reference cannot be interpreted. */
    suspend fun interpret(reference: SourceReferenceDraft): SourceReferenceInterpretationResult
}

/** How a [SourceReferenceInterpreter.interpret] ended. */
sealed interface SourceReferenceInterpretationResult {
    /** The reference was read; [segments] are in file order. */
    data class Interpreted(val segments: List<SourceReferenceInterpretation>) : SourceReferenceInterpretationResult

    /** The reference is unsupported, corrupt or does not match its file type; [reason] says which. */
    data class Rejected(val reason: String) : SourceReferenceInterpretationResult
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
