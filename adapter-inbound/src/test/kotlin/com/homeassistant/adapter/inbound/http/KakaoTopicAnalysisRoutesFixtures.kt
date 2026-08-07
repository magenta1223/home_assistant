package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal

internal object FakeAnalyzer : TopicAnalysis {
    var sourceFileName = ""
    var text = ""
    var lastUserId = ""
    var analysisCalls = 0

    fun reset() {
        sourceFileName = ""
        text = ""
        lastUserId = ""
        analysisCalls = 0
    }

    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult {
        lastUserId = request.userId
        sourceFileName = request.source.source.name
        text = request.source.records.singleOrNull()?.content.orEmpty()
        analysisCalls += 1
        if (request.source.source.name == "duplicate.txt") {
            throw DuplicateSourceRecordsException(request.source.source.name, 1)
        }
        return TopicAnalysisResult(
            sourceType = request.source.source.type,
            sourceName = request.source.source.name,
            importedRecordCount = 1,
            topics = listOf(topic(request.source.source.name, 1)),
        )
    }

    private fun topic(sourceName: String, evidenceRef: Int) =
        TopicProposal(
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            categories = listOf("relationship"),
            memories = listOf(
                MemoryProposal(
                    content = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = MemoryCertainty.OBSERVED,
                    evidenceIds = listOf(evidenceRef),
                ),
            ),
        )
}
