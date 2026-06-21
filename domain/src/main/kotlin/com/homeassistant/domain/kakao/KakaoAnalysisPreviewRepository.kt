package com.homeassistant.domain.kakao

import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.domain.db.tables.KakaoAnalysisPreviewTable
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Persists Kakao analysis previews so saving does not call the LLM again. */
class KakaoAnalysisPreviewRepository(private val db: Database) {
    fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<NewTopicCandidate>,
    ): KakaoAnalysisPreview = transaction(db) {
        val previewId = UUID.randomUUID().toString()
        KakaoAnalysisPreviewTable.insert {
            it[KakaoAnalysisPreviewTable.previewId] = previewId
            it[KakaoAnalysisPreviewTable.sourceFileName] = sourceFileName
            it[KakaoAnalysisPreviewTable.text] = text
            it[topicsJson] = topics.encodeToString()
            it[createdAt] = System.currentTimeMillis()
        }
        KakaoAnalysisPreview(previewId, sourceFileName, text, topics)
    }

    fun findPreview(previewId: String): KakaoAnalysisPreview? = transaction(db) {
        KakaoAnalysisPreviewTable.selectAll()
            .where { KakaoAnalysisPreviewTable.previewId eq previewId }
            .singleOrNull()
            ?.toPreview()
    }

    private fun ResultRow.toPreview(): KakaoAnalysisPreview =
        KakaoAnalysisPreview(
            previewId = this[KakaoAnalysisPreviewTable.previewId],
            sourceFileName = this[KakaoAnalysisPreviewTable.sourceFileName],
            text = this[KakaoAnalysisPreviewTable.text],
            topics = this[KakaoAnalysisPreviewTable.topicsJson].decodeFromString(),
        )
}

data class KakaoAnalysisPreview(
    val previewId: String,
    val sourceFileName: String,
    val text: String,
    val topics: List<NewTopicCandidate>,
)
