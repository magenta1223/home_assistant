package com.homeassistant.domain.source

/** A source record to store, identified within its source type by [deduplicationKey]. */
data class SourceRecordDraft(
    val deduplicationKey: String,
    val content: String,
    val reference: SourceReferenceDraft? = null,
    /** When this record came to be, in epoch milliseconds: the send time for a chat message, otherwise now. */
    val recordedAt: Long = System.currentTimeMillis(),
)
