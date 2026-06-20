package com.homeassistant.app.routes

import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.nlp.analysis.SourceDocument
import com.homeassistant.nlp.analysis.SourceRecord
import com.homeassistant.nlp.analysis.TopicAnalysisService
import com.homeassistant.nlp.analysis.TopicCandidate

/** Coordinates Kakao import with source-agnostic topic analysis for API callers. */
interface KakaoImportAnalyzeUseCase {
    suspend fun importAndAnalyze(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult

    suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult
}

/** API-level result for importing Kakao messages and analyzing them into topics. */
data class KakaoImportAnalyzeResult(
    val importedMessageCount: Int,
    val topics: List<TopicCandidate>,
)

class KakaoImportAnalyzeService(
    private val importService: KakaoImportService,
    private val topicAnalysisService: TopicAnalysisService,
) : KakaoImportAnalyzeUseCase {
    override suspend fun importAndAnalyze(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        val imported = importService.import(sourceFileName, text)
        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = sourceFileName,
            records = imported.messages.mapIndexed { index, message ->
                SourceRecord(
                    id = "r${index + 1}",
                    ref = message.id,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
        return KakaoImportAnalyzeResult(
            importedMessageCount = imported.importedMessageCount,
            topics = topicAnalysisService.analyze(document).topics,
        )
    }

    override suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        val messages = KakaoMessageParser.parse(sourceFileName, text)
        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = sourceFileName,
            records = messages.mapIndexed { index, message ->
                val recordNumber = index + 1
                SourceRecord(
                    id = "r$recordNumber",
                    ref = recordNumber,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
        return KakaoImportAnalyzeResult(
            importedMessageCount = messages.size,
            topics = topicAnalysisService.preview(document).topics,
        )
    }
}
