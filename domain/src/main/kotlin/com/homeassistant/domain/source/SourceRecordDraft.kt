package com.homeassistant.domain.source

data class SourceRecordDraft(
    val deduplicationKey: String,
    val content: String,
)