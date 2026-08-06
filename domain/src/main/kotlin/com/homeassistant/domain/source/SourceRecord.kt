package com.homeassistant.domain.source

data class SourceRecord(
    val id: Int,
    val deduplicationKey: String,
    val content: String,
)
