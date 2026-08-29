package com.homeassistant.domain.source

import com.homeassistant.domain.memory.MemoryAccess

data class SourceRecord(
    val id: Int,
    val deduplicationKey: String,
    val content: String,
    val analysisStatus: SourceRecordAnalysisStatus,
    val access: MemoryAccess,
    val reference: SourceReference? = null,
)
