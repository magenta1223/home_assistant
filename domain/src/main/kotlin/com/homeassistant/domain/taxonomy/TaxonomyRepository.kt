package com.homeassistant.domain.taxonomy

import com.homeassistant.domain.db.tables.TaxonomyTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class TaxonomyNode(
    val id: Int,
    val parentId: Int?,
    val name: String,
    val nodeType: String,
    val createdAt: Long,
)

class TaxonomyRepository(private val db: Database) {

    fun create(name: String, nodeType: String, parentId: Int?): Int = transaction(db) {
        TaxonomyTable.insert {
            it[TaxonomyTable.name] = name
            it[TaxonomyTable.nodeType] = nodeType
            it[TaxonomyTable.parentId] = parentId
            it[createdAt] = System.currentTimeMillis()
        }[TaxonomyTable.id]
    }

    fun list(parentId: Int?): List<TaxonomyNode> = transaction(db) {
        TaxonomyTable.selectAll().where {
            if (parentId == null) TaxonomyTable.parentId.isNull()
            else TaxonomyTable.parentId eq parentId
        }.map { it.toNode() }
    }

    fun search(query: String): List<TaxonomyNode> = transaction(db) {
        TaxonomyTable.selectAll()
            .where { TaxonomyTable.name.lowerCase() like "%${query.lowercase()}%" }
            .map { it.toNode() }
    }

    private fun ResultRow.toNode() = TaxonomyNode(
        id = this[TaxonomyTable.id],
        parentId = this[TaxonomyTable.parentId],
        name = this[TaxonomyTable.name],
        nodeType = this[TaxonomyTable.nodeType],
        createdAt = this[TaxonomyTable.createdAt],
    )
}
