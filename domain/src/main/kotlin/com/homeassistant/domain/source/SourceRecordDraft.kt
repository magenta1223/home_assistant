package com.homeassistant.domain.source

/** A canonical source record plus historical keys that may identify the same persisted record. */
data class SourceRecordDraft(
    val deduplicationKey: String,
    val content: String,
    val deduplicationAliases: Set<String> = emptySet(),
    val reference: SourceReferenceDraft? = null,
    /** When this record came to be, in epoch milliseconds: the send time for a chat message, otherwise now. */
    val recordedAt: Long = System.currentTimeMillis(),
)
