package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object MemoTable : Table("memos") {
    val id = integer("id").autoIncrement()
    val title = text("title")
    val content = text("content")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MemoTaxonomyTable : Table("memo_taxonomy") {
    val memoId = integer("memo_id").references(MemoTable.id)
    val nodeId = integer("node_id").references(TaxonomyTable.id)
    override val primaryKey = PrimaryKey(memoId, nodeId)
}
