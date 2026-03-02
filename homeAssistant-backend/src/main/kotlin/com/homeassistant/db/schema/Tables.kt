package com.homeassistant.db.schema

import org.jetbrains.exposed.sql.Table

// mirrors memos table in migrate.ts
object Memos : Table("memos") {
    val id         = integer("id").autoIncrement()
    val userId     = text("user_id")
    val isShared   = integer("is_shared").default(0)
    val title      = text("title").nullable()
    val content    = text("content")
    val tags       = text("tags").nullable()
    val createdAt  = text("created_at")
    val updatedAt  = text("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// mirrors schedules table
object Schedules : Table("schedules") {
    val id          = integer("id").autoIncrement()
    val userId      = text("user_id")
    val isShared    = integer("is_shared").default(0)
    val title       = text("title")
    val description = text("description").nullable()
    val eventDate   = text("event_date")
    val endDate     = text("end_date").nullable()
    val createdAt   = text("created_at")
    override val primaryKey = PrimaryKey(id)
}

// mirrors home_status table (unique on device_name)
object HomeStatus : Table("home_status") {
    val id         = integer("id").autoIncrement()
    val deviceName = text("device_name").uniqueIndex("idx_home_status_device")
    val status     = text("status")
    val setBy      = text("set_by")
    val updatedAt  = text("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// mirrors item_locations table (unique on item_name)
object ItemLocations : Table("item_locations") {
    val id        = integer("id").autoIncrement()
    val itemName  = text("item_name").uniqueIndex("idx_item_locations_name")
    val location  = text("location")
    val setBy     = text("set_by")
    val updatedAt = text("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// mirrors assets table
object Assets : Table("assets") {
    val id         = integer("id").autoIncrement()
    val userId     = text("user_id")
    val category   = text("category").default("cash")
    val amount     = double("amount")
    val currency   = text("currency").default("KRW")
    val note       = text("note").nullable()
    val recordedAt = text("recorded_at")
    override val primaryKey = PrimaryKey(id)
}

// mirrors todos table
object Todos : Table("todos") {
    val id        = integer("id").autoIncrement()
    val userId    = text("user_id")
    val isShared  = integer("is_shared").default(0)
    val content   = text("content")
    val isDone    = integer("is_done").default(0)
    val dueDate   = text("due_date").nullable()
    val createdAt = text("created_at")
    val doneAt    = text("done_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

// mirrors recipes table
object Recipes : Table("recipes") {
    val id          = integer("id").autoIncrement()
    val userId      = text("user_id")
    val name        = text("name")
    val ingredients = text("ingredients")
    val steps       = text("steps")
    val tags        = text("tags").nullable()
    val servings    = integer("servings").nullable()
    val createdAt   = text("created_at")
    override val primaryKey = PrimaryKey(id)
}

// mirrors grocery_items table
object GroceryItems : Table("grocery_items") {
    val id   = integer("id").autoIncrement()
    val name = text("name").uniqueIndex()
    val unit = text("unit").default("개")
    override val primaryKey = PrimaryKey(id)
}

// mirrors grocery_purchases table
object GroceryPurchases : Table("grocery_purchases") {
    val id          = integer("id").autoIncrement()
    val itemId      = integer("item_id").references(GroceryItems.id)
    val qty         = double("qty")
    val purchasedAt = text("purchased_at")
    override val primaryKey = PrimaryKey(id)
}
