package com.homeassistant.application.port.output.source

import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft

/** Stores and retrieves source records while hiding the persistence mechanism. */
interface SourceRecordRepository {

    /** Stores new drafts and returns both new and previously pending records for analysis. */
    fun saveAll(
        source: SourceDescriptor,
        records: List<SourceRecordDraft>,
        access: EpisodeAccess = EpisodeAccess.PUBLIC,
    ): SourceRecordSaveResult

    /** Returns source records belonging to the specified source. */
    fun findBySource(source: SourceDescriptor): List<SourceRecord>

}

data class SourceRecordSaveResult(
    val recordsToAnalyze: List<SourceRecord>,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
)
