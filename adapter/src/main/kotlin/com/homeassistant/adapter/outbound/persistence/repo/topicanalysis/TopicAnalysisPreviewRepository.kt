package com.homeassistant.adapter.outbound.persistence.repo.topicanalysis

import com.homeassistant.adapter.shared.json.JsonSerializer.decodeFromString
import com.homeassistant.adapter.shared.json.JsonSerializer.encodeToString
import com.homeassistant.domain.topicanalysis.ProposedTopic
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreview
import com.homeassistant.domain.topicanalysis.TopicAnalysisPreviewStore
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAnalysisPreviewTable
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
        topics: List<ProposedTopic>,
    ): TopicAnalysisPreview = transaction(db) {
        val previewId = UUID.randomUUID().toString()
        TopicAnalysisPreviewTable.insert {
            it[TopicAnalysisPreviewTable.previewId] = previewId
            it[TopicAnalysisPreviewTable.sourceFileName] = sourceFileName
            it[TopicAnalysisPreviewTable.text] = text
            it[topicsJson] = topics.encodeToString()
            it[createdAt] = System.currentTimeMillis()
        }
        TopicAnalysisPreview(previewId, sourceFileName, text, topics)
    }

    override fun findPreview(previewId: String): TopicAnalysisPreview? = transaction(db) {
        TopicAnalysisPreviewTable.selectAll()
            .where { TopicAnalysisPreviewTable.previewId eq previewId }
            .singleOrNull()
            ?.toPreview()
    }

    private fun ResultRow.toPreview(): TopicAnalysisPreview =
        TopicAnalysisPreview(
            previewId = this[TopicAnalysisPreviewTable.previewId],
            sourceName = this[TopicAnalysisPreviewTable.sourceFileName],
            text = this[TopicAnalysisPreviewTable.text],
            topics = this[TopicAnalysisPreviewTable.topicsJson].decodeFromString(),
        )
}
