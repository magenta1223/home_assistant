package com.homeassistant.domain.source

data class SourceRecordDraft(
    val sourceType: String,
    val sourceName: String,
    val deduplicationKey: String,
    val content: String,
)

data class SourceRecord(
    val id: Int,
    val sourceType: String,
    val sourceName: String,
    val deduplicationKey: String,
    val content: String,
) {
    val promptId: String get() = "r$id"
}
