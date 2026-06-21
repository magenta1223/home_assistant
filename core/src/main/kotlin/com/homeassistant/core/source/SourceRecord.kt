package com.homeassistant.core.source

/**
 * One analyzable source item with prompt id, source reference, and rendered content.
 *
 * @property id Prompt-local record id used for evidence references.
 * @property ref Stable source reference returned to callers and stored as evidence.
 * @property content Rendered source content sent to the topic analyzer.
 */
data class SourceRecord(
    val id: String,
    val ref: Int,
    val content: String,
)
