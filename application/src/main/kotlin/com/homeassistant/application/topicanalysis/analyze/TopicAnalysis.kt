package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewStore

/** Imports source records, analyzes them, and creates a reviewable topic proposal. */
interface TopicAnalysisUseCase {
    /** Imports new source records and creates a topic-analysis review. */
    suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult
}

class TopicAnalysis(
    private val topicExtractor: TopicExtractor,
    private val sourceRecords: SourceRecordRepository,
    private val reviewStore: TopicAnalysisReviewStore,
    private val accessPolicy: HouseholdAccessPolicy,
) : TopicAnalysisUseCase {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val parsedSource = request.source
        val parsedRecords = parsedSource.records
        val existingKeys = sourceRecords.findExistingDeduplicationKeys(
            parsedSource.source.type,
            parsedRecords.mapTo(mutableSetOf()) { it.deduplicationKey },
        )
        val newRecords = parsedRecords
            .filterNot { it.deduplicationKey in existingKeys }
            .distinctBy { it.deduplicationKey }
        if (parsedRecords.isNotEmpty() && newRecords.isEmpty()) {
            throw DuplicateSourceRecordsException(parsedSource.source.name, parsedRecords.size)
        }
        val storedRecords = sourceRecords.saveAll(parsedSource.source, newRecords)
        val document = SourceDocument(
            source = parsedSource.source,
            records = storedRecords,
        )
        val topics = topicExtractor.analyze(document)
        val review = reviewStore.create(
            requestedBy = userId,
            source = parsedSource.source,
            proposals = topics,
        )
        return TopicAnalysisResult(
            previewId = review.id,
            sourceType = parsedSource.source.type,
            sourceName = parsedSource.source.name,
            importedRecordCount = storedRecords.size,
            topics = topics,
        )
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }
}
