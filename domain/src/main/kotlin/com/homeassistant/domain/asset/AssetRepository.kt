package com.homeassistant.domain.asset

import com.homeassistant.domain.db.tables.AssetTable
import com.homeassistant.domain.db.tables.AssetValueHistoryTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

data class AssetRow(
    val id: Int,
    val name: String,
    val assetType: String,
    val purchasePrice: Double?,
    val currentValue: Double?,
    val currency: String,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

class AssetRepository(private val db: Database) {

    fun add(name: String, assetType: String, purchasePrice: Double?, currentValue: Double?, currency: String, notes: String?): Int = transaction(db) {
        val now = System.currentTimeMillis()
        AssetTable.insert {
            it[AssetTable.name] = name
            it[AssetTable.assetType] = assetType
            it[AssetTable.purchasePrice] = purchasePrice
            it[AssetTable.currentValue] = currentValue
            it[AssetTable.currency] = currency
            it[AssetTable.notes] = notes
            it[createdAt] = now
            it[updatedAt] = now
        }[AssetTable.id]
    }

    fun updateValue(id: Int, value: Double): Boolean = transaction(db) {
        val now = System.currentTimeMillis()
        val rows = AssetTable.update({ AssetTable.id eq id }) {
            it[currentValue] = value
            it[updatedAt] = now
        }
        if (rows > 0) {
            AssetValueHistoryTable.insert {
                it[assetId] = id
                it[AssetValueHistoryTable.value] = value
                it[recordedAt] = now
            }
        }
        rows > 0
    }

    fun list(assetType: String?): List<AssetRow> = transaction(db) {
        val query = if (assetType != null)
            AssetTable.selectAll().where { AssetTable.assetType eq assetType }
        else
            AssetTable.selectAll()
        query.map { it.toRow() }
    }

    fun summary(): Map<String, Map<String, Double>> = transaction(db) {
        AssetTable.selectAll()
            .filter { it[AssetTable.currentValue] != null }
            .groupBy { it[AssetTable.assetType] }
            .mapValues { (_, rows) ->
                rows.groupBy { it[AssetTable.currency] }
                    .mapValues { (_, currencyRows) ->
                        currencyRows.sumOf { it[AssetTable.currentValue]!! }
                    }
            }
    }

    private fun ResultRow.toRow() = AssetRow(
        id = this[AssetTable.id],
        name = this[AssetTable.name],
        assetType = this[AssetTable.assetType],
        purchasePrice = this[AssetTable.purchasePrice],
        currentValue = this[AssetTable.currentValue],
        currency = this[AssetTable.currency],
        notes = this[AssetTable.notes],
        createdAt = this[AssetTable.createdAt],
        updatedAt = this[AssetTable.updatedAt],
    )
}
