package com.homeassistant.domain.source

/** Stores and retrieves source records while hiding the persistence mechanism. */
interface SourceRecordRepository {

    /** Stores new drafts and returns both new and previously pending records for analysis. */
    fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>): SourceRecordSaveResult

    /** Returns source records belonging to the specified source. */
    fun findBySource(source: SourceDescriptor): List<SourceRecord>

}

data class SourceRecordSaveResult(
    val recordsToAnalyze: List<SourceRecord>,
    val contextRecords: List<SourceRecord>,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
)
