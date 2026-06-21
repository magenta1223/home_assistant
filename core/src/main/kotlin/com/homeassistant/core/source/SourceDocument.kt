package com.homeassistant.core.source



/**
 * Source-agnostic document passed to topic analysis.
 *
 * @property sourceType Import source category, such as kakao.
 * @property sourceName Human-readable source name or file name.
 * @property records Ordered source records available for analysis.
 */
data class SourceDocument(
    val sourceType: String,
    val sourceName: String,
    val records: List<SourceRecord>,
)
