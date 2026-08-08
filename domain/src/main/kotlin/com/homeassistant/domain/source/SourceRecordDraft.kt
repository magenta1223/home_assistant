package com.homeassistant.domain.source

/** A canonical source record plus historical keys that may identify the same persisted record. */
data class SourceRecordDraft(
    val deduplicationKey: String,
    val content: String,
    val deduplicationAliases: Set<String> = emptySet(),
)
