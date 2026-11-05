package com.homeassistant.domain.source

import com.homeassistant.domain.episode.EpisodeAccess

data class SourceRecord(
    val id: Int,
    val deduplicationKey: String,
    val content: String,
    val analysisStatus: SourceRecordAnalysisStatus,
    val access: EpisodeAccess,
    val reference: SourceReference? = null,
)
