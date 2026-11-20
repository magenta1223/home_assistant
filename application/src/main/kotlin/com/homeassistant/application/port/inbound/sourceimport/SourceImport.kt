package com.homeassistant.application.port.inbound.sourceimport

/** Accepts submitted source records and reports how each submitted import is going. */
interface SourceImport {
    /** Stores the new source records and reference of [request], queues their import job and returns it. */
    fun submit(request: SourceImportRequest): SourceImportSubmissionResult

    /** The import job [jobId] when [userId] submitted it, otherwise null. */
    fun findJob(userId: String, jobId: Int): SourceImportJobReport?
}
