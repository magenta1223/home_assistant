package com.homeassistant.domain.source



/**
 * Source-agnostic document passed to memory analysis.
 *
 * @property source Import source category and human-readable name.
 * @property records Ordered source records available for analysis.
 */
data class SourceDocument(
    val source: SourceDescriptor,
    val records: List<SourceRecord>,
)
