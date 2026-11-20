package com.homeassistant.application.port.outbound.source

import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReference
import com.homeassistant.domain.source.SourceReferenceDraft

/** Stores and retrieves source records while hiding the persistence mechanism. */
interface SourceRecordRepository {

    /**
     * Stores the new drafts of [source] as submitted by [submittedBy] with [access] and returns them with the earlier
     * records still pending analysis. An earlier record keeps its first submitter. Nothing is written when a record of
     * [source] was stored before with a different access.
     */
    fun save(
        source: SourceDescriptor,
        records: List<SourceRecordDraft>,
        submittedBy: UserId,
        access: AccessControl = AccessControl.PUBLIC,
    ): SourceRecordSaveResult

    /** Stores the file of [reference] once per content hash and returns its stored metadata. */
    fun saveReference(reference: SourceReferenceDraft): SourceReference

    /** The stored file of reference [referenceId], or null when there is none. */
    fun findReferenceContent(referenceId: Int): SourceReferenceDraft?
}

/** How a [SourceRecordRepository.save] ended. */
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
