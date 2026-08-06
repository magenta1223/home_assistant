package com.homeassistant.domain.source

interface SourceRecordStore {
    fun findExistingDeduplicationKeys(sourceType: String, keys: Set<String>): Set<String>
    fun saveAll(source: SourceDescriptor, records: List<ParsedSourceRecord>): List<SourceRecord>
    fun findBySource(source: SourceDescriptor): List<SourceRecord>
}
