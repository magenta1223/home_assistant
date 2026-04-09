package com.homeassistant.domain.memo

import com.homeassistant.domain.db.tables.MemoTable
import com.homeassistant.domain.db.tables.MemoTaxonomyTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

data class MemoRow(
    val id: Int,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nodeIds: List<Int>,
)

class MemoRepository(private val db: Database) {

    fun create(title: String, content: String, nodeIds: List<Int>): Int = transaction(db) {
        val now = System.currentTimeMillis()
        val id = MemoTable.insert {
            it[MemoTable.title] = title
            it[MemoTable.content] = content
            it[createdAt] = now
            it[updatedAt] = now
        }[MemoTable.id]
        nodeIds.forEach { nodeId ->
            MemoTaxonomyTable.insert {
                it[memoId] = id
                it[MemoTaxonomyTable.nodeId] = nodeId
            }
        }
        id
    }

    fun list(nodeId: Int?): List<MemoRow> = transaction(db) {
        val ids = if (nodeId == null) {
            MemoTable.selectAll().map { it[MemoTable.id] }
        } else {
            MemoTaxonomyTable.selectAll()
                .where { MemoTaxonomyTable.nodeId eq nodeId }
                .map { it[MemoTaxonomyTable.memoId] }
        }
        ids.mapNotNull { fetchMemo(it) }
    }

    fun search(query: String, nodeIds: List<Int>?): List<MemoRow> = transaction(db) {
        val lower = "%${query.lowercase()}%"
        val matchingIds = MemoTable.selectAll().where {
            MemoTable.title.lowerCase() like lower or (MemoTable.content.lowerCase() like lower)
        }.map { it[MemoTable.id] }

        val filtered = if (nodeIds.isNullOrEmpty()) matchingIds else {
            MemoTaxonomyTable.selectAll()
                .where { MemoTaxonomyTable.memoId inList matchingIds and (MemoTaxonomyTable.nodeId inList nodeIds) }
                .map { it[MemoTaxonomyTable.memoId] }
                .distinct()
        }
        filtered.mapNotNull { fetchMemo(it) }
    }

    fun update(id: Int, title: String?, content: String?, nodeIds: List<Int>?) = transaction(db) {
        MemoTable.update({ MemoTable.id eq id }) {
            title?.let { t -> it[MemoTable.title] = t }
            content?.let { c -> it[MemoTable.content] = c }
            it[updatedAt] = System.currentTimeMillis()
        }
        if (nodeIds != null) {
            MemoTaxonomyTable.deleteWhere { memoId eq id }
            nodeIds.forEach { nodeId ->
                MemoTaxonomyTable.insert {
                    it[memoId] = id
                    it[MemoTaxonomyTable.nodeId] = nodeId
                }
            }
        }
    }

    fun delete(id: Int): Boolean = transaction(db) {
        MemoTaxonomyTable.deleteWhere { memoId eq id }
        MemoTable.deleteWhere { MemoTable.id eq id } > 0
    }

    private fun fetchMemo(id: Int): MemoRow? {
        val row = MemoTable.selectAll().where { MemoTable.id eq id }.singleOrNull() ?: return null
        val nodeIds = MemoTaxonomyTable.selectAll()
            .where { MemoTaxonomyTable.memoId eq id }
            .map { it[MemoTaxonomyTable.nodeId] }
        return MemoRow(
            id = row[MemoTable.id],
            title = row[MemoTable.title],
            content = row[MemoTable.content],
            createdAt = row[MemoTable.createdAt],
            updatedAt = row[MemoTable.updatedAt],
            nodeIds = nodeIds,
        )
    }
}
