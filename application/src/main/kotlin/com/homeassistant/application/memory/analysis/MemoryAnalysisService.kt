package com.homeassistant.application.memory.analysis

import com.homeassistant.application.memory.save.MemoryProposalSaver
import com.homeassistant.application.memory.tree.MemoryPlacement
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecordRepository
import org.slf4j.LoggerFactory

class MemoryAnalysisService(
    private val memoryExtractor: MemoryExtractor,
    private val sourceRecords: SourceRecordRepository,
    private val memorySaver: MemoryProposalSaver,
    private val accessPolicy: HouseholdAccessPolicy,
    private val memoryPlacement: MemoryPlacement = MemoryPlacement.NoOpMemoryPlacement,
) : MemoryAnalysis {
    override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val parsedSource = request.source
        val newRecords = sourceRecords.saveAll(parsedSource.source, parsedSource.records)

        if (newRecords.isEmpty()) {
            throw DuplicateSourceRecordsException(parsedSource.source.name, parsedSource.records.size)
        }

        val proposals = memoryExtractor.analyze(
            SourceDocument(
                source = parsedSource.source,
                records = newRecords,
            ),
        )
        val savedMemories = memorySaver.save(userId, proposals)
        runCatching { memoryPlacement.place(userId, savedMemories) }
            .onFailure { error -> log.warn("Memory tree placement deferred", error) }
        return MemoryAnalysisResult(
            sourceType = parsedSource.source.type,
            sourceName = parsedSource.source.name,
            importedRecordCount = newRecords.size,
            memories = proposals,
        )
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }

    private companion object {
        val log = LoggerFactory.getLogger(MemoryAnalysisService::class.java)
    }
}
