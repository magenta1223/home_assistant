package com.homeassistant.app.routes

import com.homeassistant.domain.kakao.ImportedMessageCount
import com.homeassistant.domain.kakao.KakaoExportText
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoSourceFileName
import com.homeassistant.nlp.analysis.SourceDocument
import com.homeassistant.nlp.analysis.SourceName
import com.homeassistant.nlp.analysis.SourceRecord
import com.homeassistant.nlp.analysis.SourceRecordId
import com.homeassistant.nlp.analysis.SourceRecordRef
import com.homeassistant.nlp.analysis.SourceType
import com.homeassistant.nlp.analysis.TopicAnalysisService
import com.homeassistant.nlp.analysis.TopicCandidate

interface KakaoImportAnalyzeUseCase {
    suspend fun importAndAnalyze(
        sourceFileName: KakaoSourceFileName,
        text: KakaoExportText,
    ): KakaoImportAnalyzeResult
}

data class KakaoImportAnalyzeResult(
    val importedMessageCount: ImportedMessageCount,
    val topics: List<TopicCandidate>,
)

class KakaoImportAnalyzeService(
    private val importService: KakaoImportService,
    private val topicAnalysisService: TopicAnalysisService,
) : KakaoImportAnalyzeUseCase {
    override suspend fun importAndAnalyze(
        sourceFileName: KakaoSourceFileName,
        text: KakaoExportText,
    ): KakaoImportAnalyzeResult {
        val imported = importService.import(sourceFileName, text)
        val document = SourceDocument(
            sourceType = SourceType("kakao"),
            sourceName = SourceName(sourceFileName.value),
            records = imported.messages.mapIndexed { index, message ->
                SourceRecord(
                    id = SourceRecordId("r${index + 1}"),
                    ref = SourceRecordRef(message.id.value),
                    content = "${message.sender.value} | ${message.displayTime} | ${message.text.value}",
                )
            },
        )
        return KakaoImportAnalyzeResult(
            importedMessageCount = imported.importedMessageCount,
            topics = topicAnalysisService.analyze(document).topics,
        )
    }
}
