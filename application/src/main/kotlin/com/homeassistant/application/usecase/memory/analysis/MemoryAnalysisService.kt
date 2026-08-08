package com.homeassistant.application.usecase.memory.analysis

import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.application.port.input.memory.placement.MemoryPlaceRequest
import com.homeassistant.application.port.input.memory.placement.MemoryPlacement
import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecordRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class MemoryAnalysisService(
    private val memoryExtractor: MemoryExtractor,
    private val sourceRecords: SourceRecordRepository,
    private val memorySaver: MemoryProposalsPersister,
    private val accessPolicy: HouseholdAccessPolicy,
    private val memoryPlacement: MemoryPlacement = MemoryPlacement.NoOpMemoryPlacement,
) : MemoryAnalysis {
    override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val parsedSource = request.source
        val sourceRecordSave = requireAvailable {
            sourceRecords.saveAll(parsedSource.source, parsedSource.records)
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
        val savedMemories = requireAvailable { memorySaver.persist(userId, proposals) }
        requireAvailable { sourceRecords.markAnalyzed(recordsToAnalyze.map { it.id }) }
        runCatching { memoryPlacement.place(MemoryPlaceRequest(userId, savedMemories)) }
            .onFailure { error -> log.warn("Memory tree placement deferred", error) }
        return MemoryAnalysisResult(
            sourceType = parsedSource.source.type,
            sourceName = parsedSource.source.name,
            importedRecordCount = sourceRecordSave.importedRecordCount,
            retriedRecordCount = sourceRecordSave.retriedRecordCount,
            alreadyAnalyzedRecordCount = sourceRecordSave.alreadyAnalyzedRecordCount,
            publicMemoryCount = proposals.count { it.visibility == MemoryVisibility.PUBLIC },
            privateMemoryCount = proposals.count { it.visibility == MemoryVisibility.PRIVATE },
            memories = proposals,
        )
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
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
