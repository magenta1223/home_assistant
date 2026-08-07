package com.homeassistant.domain.source

/** Stores and retrieves source records while hiding the persistence mechanism. */
interface SourceRecordRepository {

    /** Stores drafts and returns their identity-bearing source records. */
    fun saveAll(source: SourceDescriptor, records: List<SourceRecordDraft>): List<SourceRecord>

    /** Returns source records belonging to the specified source. */
    fun findBySource(source: SourceDescriptor): List<SourceRecord>
}
