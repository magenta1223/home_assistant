package com.homeassistant.application.usecase.memory.analysis

import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.application.port.input.memory.analysis.InvalidMemoryAudienceException
import com.homeassistant.application.port.input.memory.analysis.InvalidKnowledgeReferenceException
import com.homeassistant.application.port.input.memory.analysis.ConflictingSourceAudienceException
import com.homeassistant.application.port.input.memory.placement.MemoryPlaceRequest
import com.homeassistant.application.port.input.memory.placement.MemoryPlacement
import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.application.port.output.source.SourceReferenceInterpreter
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.InvalidSourceReferenceException
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceAccessConflictException
import com.homeassistant.domain.source.SourceRecordRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class MemoryAnalysisService(
    private val memoryExtractor: MemoryExtractor,
    private val sourceRecords: SourceRecordRepository,
    private val memorySaver: MemoryProposalsPersister,
    private val accessPolicy: UserAccessPolicy,
    private val memoryPlacement: MemoryPlacement = MemoryPlacement.NoOpMemoryPlacement,
    private val memoryIndexing: MemoryIndexingOutboxProcessor? = null,
    private val referenceInterpreter: SourceReferenceInterpreter? = null,
) : MemoryAnalysis {
    override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        requireAuthorizedAudience(request.access.allowedUserIds)
        val parsedSource = interpretReference(request)
        val sourceRecordSave = try {
            requireAvailable {
                sourceRecords.saveAll(parsedSource.source, parsedSource.records, request.access)
            }
        } catch (error: MemoryAnalysisUnavailableException) {
            val conflict = error.cause as? SourceAccessConflictException
            if (conflict != null) {
                throw ConflictingSourceAudienceException(parsedSource.source.name, conflict.existingAccess)
            }
            throw error
        }
        val recordsToAnalyze = sourceRecordSave.recordsToAnalyze

        if (recordsToAnalyze.isEmpty() && parsedSource.records.isNotEmpty()) {
            throw DuplicateSourceRecordsException(
                parsedSource.source.name,
                sourceRecordSave.alreadyAnalyzedRecordCount,
            )
        }

        val contextRecords = sourceRecordSave.contextRecords.takeLast(CONTEXT_RECORD_LIMIT)

        val proposals = requireAvailable {
            memoryExtractor.analyze(
                SourceDocument(
                    source = parsedSource.source,
                    contextRecords = contextRecords,
                    records = recordsToAnalyze,
                ),
            )
        }
        val savedMemories = requireAvailable {
            memorySaver.persist(userId, proposals, recordsToAnalyze.map { it.id })
        }
        runCatching { memoryIndexing?.processAvailable() }
            .onFailure { error -> log.warn("Memory indexing deferred", error) }
        runCatching { memoryPlacement.place(MemoryPlaceRequest(userId, savedMemories)) }
            .onFailure { error -> log.warn("Memory tree placement deferred", error) }
        return MemoryAnalysisResult(
            sourceType = parsedSource.source.type,
            sourceName = parsedSource.source.name,
            importedRecordCount = sourceRecordSave.importedRecordCount,
            retriedRecordCount = sourceRecordSave.retriedRecordCount,
            alreadyAnalyzedRecordCount = sourceRecordSave.alreadyAnalyzedRecordCount,
            visibility = request.access.visibility,
            allowedUserIds = request.access.allowedUserIds,
            memoryCount = proposals.size,
            memories = proposals,
        )
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw UserAccessDeniedException()
    }

    private suspend fun interpretReference(request: MemoryAnalysisRequest) = request.source.reference?.let { reference ->
        val interpreter = referenceInterpreter
            ?: throw MemoryAnalysisUnavailableException(IllegalStateException("reference interpreter is unavailable"))
        val interpretations = try {
            interpreter.interpret(reference)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InvalidSourceReferenceException) {
            throw InvalidKnowledgeReferenceException(
                error.message ?: "invalid reference",
                error,
            )
        } catch (error: Exception) {
            throw MemoryAnalysisUnavailableException(error)
        }
        if (interpretations.isEmpty()) {
            throw InvalidKnowledgeReferenceException(
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
        if (invalid.isNotEmpty()) throw InvalidMemoryAudienceException(invalid)
    }

    private suspend fun <T> requireAvailable(operation: suspend () -> T): T =
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw MemoryAnalysisUnavailableException(error)
        }

    private companion object {
        const val CONTEXT_RECORD_LIMIT = 20
        val log = LoggerFactory.getLogger(MemoryAnalysisService::class.java)
    }
}
