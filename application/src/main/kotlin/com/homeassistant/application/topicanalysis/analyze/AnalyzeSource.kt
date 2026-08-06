package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordStore
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore

interface AnalyzeSourceUseCase {
    suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult
}

class AnalyzeSource(
    private val topicExtractor: TopicExtractor,
    private val sourceTextParser: SourceTextParser,
    private val sourceRecords: SourceRecordStore,
    private val previewRepository: TopicAnalysisPreviewStore,
    private val accessPolicy: HouseholdAccessPolicy,
) : AnalyzeSourceUseCase {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val parsedSource = sourceTextParser.parse(request.sourceName, request.text)
        val expectedSource = SourceDescriptor(request.sourceType, request.sourceName)
        require(parsedSource.source == expectedSource) { "Source parser returned a different source" }
        val parsedRecords = parsedSource.records
        val existingKeys = sourceRecords.findExistingDeduplicationKeys(
            request.sourceType,
            parsedRecords.mapTo(mutableSetOf()) { it.deduplicationKey },
        )
        val newRecords = parsedRecords
            .filterNot { it.deduplicationKey in existingKeys }
            .distinctBy { it.deduplicationKey }
        if (parsedRecords.isNotEmpty() && newRecords.isEmpty()) {
            throw DuplicateSourceRecordsException(request.sourceName, parsedRecords.size)
        }
        val storedRecords = sourceRecords.saveAll(parsedSource.source, newRecords)
        val document = SourceDocument(
            source = parsedSource.source,
            records = storedRecords,
        )
        val topics = topicExtractor.analyze(document)
        val preview = previewRepository.createPreview(
            requestedByUserId = userId.value,
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            topics = topics,
        )
        return TopicAnalysisResult(
            previewId = preview.previewId,
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = storedRecords.size,
            topics = topics,
        )
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }
}
