package com.homeassistant.domain.source

interface SourceRecordStore {
    fun findExistingDeduplicationKeys(sourceType: String, keys: Set<String>): Set<String>
    fun saveAll(records: List<SourceRecordDraft>): List<SourceRecord>
    fun findBySource(sourceType: String, sourceName: String): List<SourceRecord>
}
