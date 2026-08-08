package com.homeassistant.domain.source



/**
 * Source-agnostic document passed to memory analysis.
 *
 * @property source Import source category and human-readable name.
 * @property contextRecords Previously analyzed records provided only as read-only context.
 * @property records Ordered source records available for analysis.
 */
data class SourceDocument(
    val source: SourceDescriptor,
    val contextRecords: List<SourceRecord> = emptyList(),
    val records: List<SourceRecord>,
)
