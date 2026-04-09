package com.homeassistant.domain.grocery

import com.homeassistant.domain.db.tables.GroceryItemTable
import com.homeassistant.domain.db.tables.GroceryPurchaseTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit

data class GroceryItemStats(
    val id: Int,
    val name: String,
    val lastPurchasedAt: Long?,
    val avgIntervalDays: Double?,
)

class GroceryRepository(private val db: Database) {

    fun recordPurchase(itemName: String, quantity: Double, purchasedAt: Long?): Int = transaction(db) {
        val existingId = GroceryItemTable.selectAll()
            .where { GroceryItemTable.name eq itemName }
            .singleOrNull()?.get(GroceryItemTable.id)

        val itemId = existingId ?: GroceryItemTable.insert {
            it[name] = itemName
        }[GroceryItemTable.id]

        GroceryPurchaseTable.insert {
            it[groceryItemId] = itemId
            it[GroceryPurchaseTable.quantity] = quantity
            it[GroceryPurchaseTable.purchasedAt] = purchasedAt ?: System.currentTimeMillis()
        }[GroceryPurchaseTable.id]
    }

    fun list(): List<GroceryItemStats> = transaction(db) {
        GroceryItemTable.selectAll().map { itemRow ->
            val itemId = itemRow[GroceryItemTable.id]
            val purchases = GroceryPurchaseTable.selectAll()
                .where { GroceryPurchaseTable.groceryItemId eq itemId }
                .orderBy(GroceryPurchaseTable.purchasedAt, SortOrder.ASC)
                .map { it[GroceryPurchaseTable.purchasedAt] }

            val lastPurchasedAt = purchases.lastOrNull()
            val avgIntervalDays = if (purchases.size >= 2) {
                val intervals = purchases.zipWithNext { a, b -> (b - a).toDouble() }
                val avgMs = intervals.average()
                avgMs / TimeUnit.DAYS.toMillis(1)
            } else null

            GroceryItemStats(itemId, itemRow[GroceryItemTable.name], lastPurchasedAt, avgIntervalDays)
        }
    }

    fun due(): List<GroceryItemStats> {
        val now = System.currentTimeMillis()
        return list().filter { stats ->
            val avg = stats.avgIntervalDays ?: return@filter false
            val last = stats.lastPurchasedAt ?: return@filter false
            val daysSinceLast = (now - last).toDouble() / TimeUnit.DAYS.toMillis(1)
            daysSinceLast >= avg
        }
    }
}
