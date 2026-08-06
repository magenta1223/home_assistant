package com.homeassistant.domain.source

data class ParsedSource(
    val source: SourceDescriptor,
    val records: List<ParsedSourceRecord>,
)

data class ParsedSourceRecord(
    val deduplicationKey: String,
    val content: String,
)
