package com.homeassistant.application.usecase.memoryextraction.episode

import com.homeassistant.application.port.inbound.memoryextraction.SourceAccessConflictException
import com.homeassistant.application.port.inbound.memoryextraction.DuplicateSourceRecordsException
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisResult
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisUnavailableException
import com.homeassistant.application.port.inbound.memoryextraction.InvalidAllowedUsersException
import com.homeassistant.application.port.inbound.memoryextraction.InvalidSourceReferenceException
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeExtractor
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretation
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretationResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpreter
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.application.port.inbound.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class EpisodeAnalysisService(
    private val episodeExtractor: EpisodeExtractor,
    private val sourceRecords: SourceRecordRepository,
    private val episodes: EpisodeRepository,
    private val accessPolicy: UserAccessPolicy,
    private val referenceInterpreter: SourceReferenceInterpreter? = null,
) : EpisodeAnalysis {
    override suspend fun execute(request: EpisodeAnalysisRequest): EpisodeAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        requireAuthorizedAudience(request.access.allowedUserIds)
        val parsedSource = withReferenceRecords(request.source)
        val sourceRecordSave = saveSourceRecords(parsedSource, request.access)
        val recordsToAnalyze = sourceRecordSave.recordsToAnalyze

        if (recordsToAnalyze.isEmpty() && parsedSource.records.isNotEmpty()) {
            throw DuplicateSourceRecordsException(
                parsedSource.source.name,
                sourceRecordSave.alreadyAnalyzedRecordCount,
            )
        }

        val proposals = rethrowAsUnavailable {
            episodeExtractor.analyze(
                SourceDocument(
                    source = parsedSource.source,
                    records = recordsToAnalyze,
                ),
            )
        }
        rethrowAsUnavailable {
            episodes.save(EpisodeBatch(userId, proposals, recordsToAnalyze.map { it.id }))
        }
        return EpisodeAnalysisResult(
            sourceType = parsedSource.source.type,
            sourceName = parsedSource.source.name,
            importedRecordCount = sourceRecordSave.importedRecordCount,
            retriedRecordCount = sourceRecordSave.retriedRecordCount,
            alreadyAnalyzedRecordCount = sourceRecordSave.alreadyAnalyzedRecordCount,
            visibility = request.access.visibility,
            allowedUserIds = request.access.allowedUserIds,
            episodeCount = proposals.size,
            episodes = proposals,
        )
    }

    /** Saves [source]'s records, reporting an access conflict apart from an unavailable store. */
    private fun saveSourceRecords(source: SourceDocumentDraft, access: AccessControl): SourceRecordSaveResult.Saved {
        val result = try {
            sourceRecords.save(source.source, source.records, access)
        } catch (error: Exception) {
            throw EpisodeAnalysisUnavailableException(error)
        }
        return when (result) {
            is SourceRecordSaveResult.Saved -> result
            is SourceRecordSaveResult.AccessConflict ->
                throw SourceAccessConflictException(source.source.name, result.existingAccess)
        }
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
    }

    /** [source] with its reference turned into records, or [source] itself when it has no reference. */
    private suspend fun withReferenceRecords(source: SourceDocumentDraft): SourceDocumentDraft =
        when (val reference = source.reference) {
            null -> source
            else -> source.copy(records = source.records + referenceRecords(reference), reference = null)
        }

    /** One record per interpreted segment of [reference], in file order. */
    private suspend fun referenceRecords(reference: SourceReferenceDraft): List<SourceRecordDraft> =
        interpretSegments(reference).map { it.toRecordOf(reference) }

    /** The segments of [reference]; rejects an unavailable interpreter, a rejected file and an empty result. */
    private suspend fun interpretSegments(reference: SourceReferenceDraft): List<SourceReferenceInterpretation> {
        val interpreter = referenceInterpreter
            ?: throw EpisodeAnalysisUnavailableException(IllegalStateException("reference interpreter is unavailable"))
        val segments = when (val result = rethrowAsUnavailable { interpreter.interpret(reference) }) {
            is SourceReferenceInterpretationResult.Interpreted -> result.segments
            is SourceReferenceInterpretationResult.Rejected -> throw InvalidSourceReferenceException(result.reason)
        }
        if (segments.isEmpty()) {
            throw InvalidSourceReferenceException("reference did not contain interpretable content")
        }
        return segments
    }

    /** This segment as a record of [reference], keyed by the file hash and segment so a re-upload is deduplicated. */
    private fun SourceReferenceInterpretation.toRecordOf(reference: SourceReferenceDraft): SourceRecordDraft =
        SourceRecordDraft(
            deduplicationKey = "reference:${reference.sha256}:$segmentKey",
            content = buildString {
                appendLine("[reference: ${reference.fileName}, $segmentKey]")
                append(content.trim())
            },
            reference = reference,
        )

    private fun requireAuthorizedAudience(userIds: Set<String>) {
        val invalid = userIds.filterTo(linkedSetOf()) { raw ->
            runCatching { UserId(raw) }.getOrNull()?.let(accessPolicy::isAuthorized) != true
        }
        if (invalid.isNotEmpty()) throw InvalidAllowedUsersException(invalid)
    }

    /** The result of [operation], rethrowing its failure as [EpisodeAnalysisUnavailableException]. */
    private suspend fun <T> rethrowAsUnavailable(operation: suspend () -> T): T =
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EpisodeAnalysisUnavailableException(error)
        }

    private companion object {
        val log = LoggerFactory.getLogger(EpisodeAnalysisService::class.java)
    }
}