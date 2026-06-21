package com.homeassistant.repository.repo.topicanalysis

import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import com.homeassistant.core.utils.JsonSerializer.encodeToString
import com.homeassistant.datamodel.kakao.KakaoAnalysisPreview
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.repository.db.tables.TopicAnalysisPreviewTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Persists Kakao analysis previews so saving does not call the LLM again. */
internal class TopicAnalysisPreviewRepository(private val db: Database) : TopicAnalysisPreviewStore {
    override fun createPreview(
        sourceFileName: String,
        text: String,
        topics: List<TopicCandidate>,
    ): KakaoAnalysisPreview = transaction(db) {
        val previewId = UUID.randomUUID().toString()
        TopicAnalysisPreviewTable.insert {
            it[TopicAnalysisPreviewTable.previewId] = previewId
            it[TopicAnalysisPreviewTable.sourceFileName] = sourceFileName
            it[TopicAnalysisPreviewTable.text] = text
            it[topicsJson] = topics.encodeToString()
            it[createdAt] = System.currentTimeMillis()
        }
        KakaoAnalysisPreview(previewId, sourceFileName, text, topics)
    }

    override fun findPreview(previewId: String): KakaoAnalysisPreview? = transaction(db) {
        TopicAnalysisPreviewTable.selectAll()
            .where { TopicAnalysisPreviewTable.previewId eq previewId }
            .singleOrNull()
            ?.toPreview()
    }

    private fun ResultRow.toPreview(): KakaoAnalysisPreview =
        KakaoAnalysisPreview(
            previewId = this[TopicAnalysisPreviewTable.previewId],
            sourceFileName = this[TopicAnalysisPreviewTable.sourceFileName],
            text = this[TopicAnalysisPreviewTable.text],
            topics = this[TopicAnalysisPreviewTable.topicsJson].decodeFromString(),
        )
}