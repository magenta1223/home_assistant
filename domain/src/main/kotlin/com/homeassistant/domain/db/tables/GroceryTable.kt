package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

object GroceryItemTable : Table("grocery_items") {
    val id = integer("id").autoIncrement()
    val name = text("name").uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object GroceryPurchaseTable : Table("grocery_purchases") {
    val id = integer("id").autoIncrement()
    val groceryItemId = integer("grocery_item_id").references(GroceryItemTable.id)
    val quantity = double("quantity")
    val purchasedAt = long("purchased_at")
    override val primaryKey = PrimaryKey(id)
}
