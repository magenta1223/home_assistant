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
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretationResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpreter
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.application.port.inbound.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
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
        val parsedSource = interpretReference(request)
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
            sourceRecords.saveAll(source.source, source.records, access)
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

    private suspend fun interpretReference(request: EpisodeAnalysisRequest) = request.source.reference?.let { reference ->
        val interpreter = referenceInterpreter
            ?: throw EpisodeAnalysisUnavailableException(IllegalStateException("reference interpreter is unavailable"))
        val interpretations = when (val result = rethrowAsUnavailable { interpreter.interpret(reference) }) {
            is SourceReferenceInterpretationResult.Interpreted -> result.segments
            is SourceReferenceInterpretationResult.Rejected -> throw InvalidSourceReferenceException(result.reason)
        }
        if (interpretations.isEmpty()) {
            throw InvalidSourceReferenceException(
                "reference did not contain interpretable content",
            )
        }
        val referenceRecords = interpretations.map { interpretation ->
            SourceRecordDraft(
                deduplicationKey = "reference:${reference.sha256}:${interpretation.segmentKey}",
                content = buildString {
                    appendLine("[reference: ${reference.fileName}, ${interpretation.segmentKey}]")
                    append(interpretation.content.trim())
                },
                reference = reference,
            )
        }
        request.source.copy(
            records = request.source.records + referenceRecords,
            reference = null,
        )
    } ?: request.source

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