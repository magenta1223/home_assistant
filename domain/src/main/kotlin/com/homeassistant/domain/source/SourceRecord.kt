package com.homeassistant.domain.source

import com.homeassistant.domain.access.AccessControl

data class SourceRecord(
    val id: Int,
    val deduplicationKey: String,
    val content: String,
    val analysisStatus: SourceRecordAnalysisStatus,
    val access: AccessControl,
    val reference: SourceReference? = null,
)
