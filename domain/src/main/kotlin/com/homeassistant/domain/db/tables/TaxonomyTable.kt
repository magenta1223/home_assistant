package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object TaxonomyTable : Table("taxonomy_nodes") {
    val id = integer("id").autoIncrement()
    val parentId = integer("parent_id").nullable()
    val name = text("name")
    val nodeType = text("node_type")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
