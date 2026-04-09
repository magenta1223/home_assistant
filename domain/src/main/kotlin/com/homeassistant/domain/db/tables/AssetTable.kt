package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object AssetTable : Table("assets") {
    val id = integer("id").autoIncrement()
    val name = text("name")
    val assetType = text("asset_type")
    val purchasePrice = double("purchase_price").nullable()
    val currentValue = double("current_value").nullable()
    val currency = text("currency")
    val notes = text("notes").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AssetValueHistoryTable : Table("asset_value_history") {
    val id = integer("id").autoIncrement()
    val assetId = integer("asset_id").references(AssetTable.id)
    val value = double("value")
    val recordedAt = long("recorded_at")
    override val primaryKey = PrimaryKey(id)
}
