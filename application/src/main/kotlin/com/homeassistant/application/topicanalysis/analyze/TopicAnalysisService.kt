package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.topicanalysis.save.TopicProposalSaver

class TopicAnalysisService(
    private val topicExtractor: TopicExtractor,
    private val sourceRecords: SourceRecordRepository,
    private val topicSaver: TopicProposalSaver,
    private val accessPolicy: HouseholdAccessPolicy,
) : TopicAnalysis {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        val userId = UserId(request.userId)
        requireAuthorized(userId)
        val parsedSource = request.source

        val newRecords = sourceRecords.saveAll(parsedSource.source, parsedSource.records)

        if (newRecords.isEmpty()) {
            throw DuplicateSourceRecordsException(parsedSource.source.name, parsedSource.records.size)
        }

        val document = SourceDocument(
            source = parsedSource.source,
            records = newRecords,
        )
        val topics = topicExtractor.analyze(document)
        topicSaver.save(userId, parsedSource.source, topics)
        return TopicAnalysisResult(
            sourceType = parsedSource.source.type,
            sourceName = parsedSource.source.name,
            importedRecordCount = newRecords.size,
            topics = topics,
        )
    }

    private fun requireAuthorized(userId: UserId) {
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
    }
}
