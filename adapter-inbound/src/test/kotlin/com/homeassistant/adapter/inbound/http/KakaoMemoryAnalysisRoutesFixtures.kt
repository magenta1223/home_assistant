package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.memory.analysis.MemoryAnalysis
import com.homeassistant.application.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.memory.analysis.MemoryAnalysisResult
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType

internal object FakeAnalyzer : MemoryAnalysis {
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

    override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
        lastUserId = request.userId
        sourceFileName = request.source.source.name
        text = request.source.records.singleOrNull()?.content.orEmpty()
        analysisCalls += 1
        if (request.source.source.name == "duplicate.txt") {
            throw DuplicateSourceRecordsException(request.source.source.name, 1)
        }
        return MemoryAnalysisResult(
            sourceType = request.source.source.type,
            sourceName = request.source.source.name,
            importedRecordCount = 1,
            memories = listOf(
                MemoryProposal(
                    content = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = MemoryCertainty.OBSERVED,
                    evidenceIds = listOf(1),
                ),
            ),
        )
    }
}
