package com.homeassistant.context

import com.homeassistant.models.ContextResult
import com.homeassistant.models.ContextSpec
import com.homeassistant.models.FilterParams
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ContextRetriever::class.java)

private const val RECENT_LIMIT = 10
private const val SIMILAR_LIMIT = 5

private data class TableMeta(
    val textCol: String,
    val hasUserId: Boolean,
    val hasCategory: Boolean = false,
    val dateCol: String? = null,
    val vecTable: String? = null,
)

private val TABLE_META: Map<String, TableMeta> = mapOf(
    "memos"          to TableMeta("content",     true,  dateCol = "created_at", vecTable = "vec_memos"),
    "todos"          to TableMeta("content",     true,  dateCol = "created_at", vecTable = "vec_todos"),
    "schedules"      to TableMeta("title",       true,  dateCol = "event_date"),
    "home_status"    to TableMeta("device_name", false, dateCol = "updated_at"),
    "item_locations" to TableMeta("item_name",   false, dateCol = "updated_at"),
    "assets"         to TableMeta("category",    true,  hasCategory = true, dateCol = "recorded_at"),
    "recipes"        to TableMeta("name",        true,  dateCol = "created_at", vecTable = "vec_recipes"),
    "grocery_items"  to TableMeta("name",        false),
)

private val ALLOWED_TABLES = TABLE_META.keys.toSet()

class ContextRetriever(private val embedding: EmbeddingService) {

    fun retrieve(specs: List<ContextSpec>, userId: String): List<ContextResult> =
        specs.map { retrieveOne(it, userId) }

    fun storeEmbedding(table: String, rowId: Long, text: String) {
        val meta = TABLE_META[table] ?: return
        val vecTable = meta.vecTable ?: return
        val vector = embedding.embed(text) ?: return
        try {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            embedding.store(conn, vecTable, rowId, vector)
        } catch (e: Exception) {
            log.warn("storeEmbedding failed for $table/$rowId: ${e.message}")
        }
    }

    private fun retrieveOne(spec: ContextSpec, userId: String): ContextResult =
        when (spec.type) {
            "recent"  -> retrieveRecent(spec.db, userId)
            "query"   -> retrieveQuery(spec.db, userId, spec.filter ?: FilterParams())
            "similar" -> retrieveSimilar(spec.db, userId, spec.searchText ?: "")
            else      -> ContextResult(spec.db, spec.type, emptyList())
        }

    private fun retrieveRecent(table: String, userId: String): ContextResult {
        if (table !in ALLOWED_TABLES) return ContextResult(table, "recent", emptyList())
        val meta = TABLE_META[table]!!
        val dateCol = meta.dateCol ?: "rowid"
        val rows = transaction {
            val sql = if (meta.hasUserId)
                "SELECT * FROM $table WHERE (user_id = ? OR is_shared = 1) ORDER BY $dateCol DESC LIMIT ?"
            else
                "SELECT * FROM $table ORDER BY $dateCol DESC LIMIT ?"
            execRaw(sql, if (meta.hasUserId) listOf(userId, RECENT_LIMIT) else listOf(RECENT_LIMIT))
        }
        return ContextResult(table, "recent", rows)
    }

    private fun retrieveQuery(table: String, userId: String, filter: FilterParams): ContextResult {
        if (table !in ALLOWED_TABLES) return ContextResult(table, "query", emptyList())
        val meta = TABLE_META[table]!!
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (meta.hasUserId) {
            conditions.add("(user_id = ? OR is_shared = 1)")
            params.add(userId)
        }
        filter.keyword?.let {
            conditions.add("${meta.textCol} LIKE ?")
            params.add("%$it%")
        }
        if (filter.dateFrom != null && meta.dateCol != null) {
            conditions.add("date(${meta.dateCol}) >= ?")
            params.add(filter.dateFrom)
        }
        if (filter.dateTo != null && meta.dateCol != null) {
            conditions.add("date(${meta.dateCol}) <= ?")
            params.add(filter.dateTo)
        }
        if (filter.category != null && meta.hasCategory) {
            conditions.add("category = ?")
            params.add(filter.category)
        }
        if (filter.isShared != null && !meta.hasUserId) {
            conditions.add("is_shared = ?")
            params.add(if (filter.isShared) 1 else 0)
        }

        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val rows = transaction {
            execRaw("SELECT * FROM $table $where LIMIT ?", params + RECENT_LIMIT)
        }
        return ContextResult(table, "query", rows)
    }

    private fun retrieveSimilar(table: String, userId: String, searchText: String): ContextResult {
        if (table !in ALLOWED_TABLES) return ContextResult(table, "similar", emptyList())
        val meta = TABLE_META[table]!!
        if (meta.vecTable == null) return retrieveRecent(table, userId)

        val queryEmbedding = embedding.embed(searchText) ?: return retrieveRecent(table, userId)

        val similar = transaction {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            embedding.findSimilar(conn, meta.vecTable, queryEmbedding, SIMILAR_LIMIT)
        }
        if (similar.isEmpty()) return ContextResult(table, "similar", emptyList())

        val ids = similar.map { it.rowid }
        val placeholders = ids.joinToString(",") { "?" }
        val rows = transaction {
            execRaw("SELECT * FROM $table WHERE id IN ($placeholders)", ids)
        }
        return ContextResult(table, "similar", rows)
    }

    /** Execute a raw SQL SELECT and return results as list of maps. */
    private fun execRaw(sql: String, params: List<Any>): List<Map<String, Any?>> {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val results = mutableListOf<Map<String, Any?>>()
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, v ->
                when (v) {
                    is Int    -> stmt.setInt(i + 1, v)
                    is Long   -> stmt.setLong(i + 1, v)
                    is String -> stmt.setString(i + 1, v)
                    else      -> stmt.setObject(i + 1, v)
                }
            }
            val rs = stmt.executeQuery()
            val meta = rs.metaData
            while (rs.next()) {
                val row = mutableMapOf<String, Any?>()
                for (col in 1..meta.columnCount) {
                    row[meta.getColumnName(col)] = rs.getObject(col)
                }
                results.add(row)
            }
        }
        return results
    }
}
