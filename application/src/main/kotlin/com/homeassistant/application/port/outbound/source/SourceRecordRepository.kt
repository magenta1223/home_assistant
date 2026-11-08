package com.homeassistant.application.port.outbound.source

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft

/** Stores and retrieves source records while hiding the persistence mechanism.
 * TODO: 다른 객체 저장소와 유사하게 read, write분리
 * */
interface SourceRecordRepository {

    /**
     * Stores the new drafts of [source] with [access] and returns them with the earlier records still pending analysis.
     * Nothing is written when a record of [source] was stored before with a different access.
     */
    fun saveAll(
        source: SourceDescriptor,
        records: List<SourceRecordDraft>,
        access: AccessControl = AccessControl.PUBLIC,
    ): SourceRecordSaveResult

}

/** How a [SourceRecordRepository.saveAll] ended. */
sealed interface SourceRecordSaveResult {
    /** The new drafts were stored; [recordsToAnalyze] holds them and the earlier pending records, in draft order. */
    data class Saved(
        val recordsToAnalyze: List<SourceRecord>,
        val importedRecordCount: Int,
        val retriedRecordCount: Int,
        val alreadyAnalyzedRecordCount: Int,
    ) : SourceRecordSaveResult

    /** A record was stored before with [existingAccess], which differs from the submitted access; nothing was written. */
    data class AccessConflict(val existingAccess: AccessControl) : SourceRecordSaveResult
}
