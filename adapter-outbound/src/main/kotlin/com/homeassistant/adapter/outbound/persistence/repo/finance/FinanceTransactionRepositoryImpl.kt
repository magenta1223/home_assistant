package com.homeassistant.adapter.outbound.persistence.repo.finance

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.FinanceTransactionTable
import com.homeassistant.application.port.outbound.finance.FinanceTransactionCriteria
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal class FinanceTransactionRepositoryImpl(private val db: SqliteDatabases) : FinanceTransactionRepository {

    override fun save(transactions: List<FinanceTransaction>): List<FinanceTransaction> = db.write {
        val saved = transactions.associateBy { it.ownerId to it.transactionKey }.values.toList()
        FinanceTransactionTable.batchUpsert(
            saved,
            FinanceTransactionTable.userId,
            FinanceTransactionTable.transactionKey,
            onUpdate = {
                it[FinanceTransactionTable.type] = insertValue(FinanceTransactionTable.type)
                it[FinanceTransactionTable.majorCategory] = insertValue(FinanceTransactionTable.majorCategory)
                it[FinanceTransactionTable.minorCategory] = insertValue(FinanceTransactionTable.minorCategory)
                it[FinanceTransactionTable.memo] = insertValue(FinanceTransactionTable.memo)
            },
            shouldReturnGeneratedValues = false,
        ) { transaction ->
            this[FinanceTransactionTable.userId] = transaction.ownerId.value
            this[FinanceTransactionTable.transactionKey] = transaction.transactionKey
            this[FinanceTransactionTable.occurredAt] = transaction.occurredAt.toEpochMillis()
            this[FinanceTransactionTable.type] = transaction.type
            this[FinanceTransactionTable.majorCategory] = transaction.majorCategory
            this[FinanceTransactionTable.minorCategory] = transaction.minorCategory
            this[FinanceTransactionTable.description] = transaction.description
            this[FinanceTransactionTable.amount] = transaction.amount
            this[FinanceTransactionTable.currency] = transaction.currency
            this[FinanceTransactionTable.paymentMethod] = transaction.paymentMethod
            this[FinanceTransactionTable.memo] = transaction.memo
        }
        saved
    }

    override fun findMatching(criteria: FinanceTransactionCriteria): List<FinanceTransaction> = db.read {
        FinanceTransactionTable.selectAll()
            .where { matching(criteria) }
            .orderBy(FinanceTransactionTable.occurredAt)
            .map { it.toFinanceTransaction() }
    }

    /** The SQL condition of [criteria]; a criterion that is not given adds no condition. */
    private fun matching(criteria: FinanceTransactionCriteria): Op<Boolean> =
        listOfNotNull(
            FinanceTransactionTable.userId eq criteria.ownerId.value,
            FinanceTransactionTable.occurredAt greaterEq criteria.from.toEpochMillis(),
            FinanceTransactionTable.occurredAt less criteria.to.toEpochMillis(),
            criteria.category?.let {
                (FinanceTransactionTable.majorCategory eq it) or (FinanceTransactionTable.minorCategory eq it)
            },
            criteria.descriptionContains?.let { FinanceTransactionTable.description like containing(it) },
        ).reduce(Op<Boolean>::and)

    /** Matches text that contains [part] anywhere, with the wildcards in [part] taken literally. */
    private fun containing(part: String): LikePattern =
        LikePattern("%", ESCAPE) + LikePattern.ofLiteral(part, ESCAPE) + "%"

    private fun ResultRow.toFinanceTransaction(): FinanceTransaction = FinanceTransaction(
        ownerId = UserId(this[FinanceTransactionTable.userId]),
        occurredAt = this[FinanceTransactionTable.occurredAt].toLocalDateTime(),
        type = this[FinanceTransactionTable.type],
        majorCategory = this[FinanceTransactionTable.majorCategory],
        minorCategory = this[FinanceTransactionTable.minorCategory],
        description = this[FinanceTransactionTable.description],
        amount = this[FinanceTransactionTable.amount],
        currency = this[FinanceTransactionTable.currency],
        paymentMethod = this[FinanceTransactionTable.paymentMethod],
        memo = this[FinanceTransactionTable.memo],
    )

    private fun LocalDateTime.toEpochMillis(): Long = atZone(LEDGER_ZONE).toInstant().toEpochMilli()

    private fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), LEDGER_ZONE)

    private companion object {
        /** The zone of [FinanceTransaction.occurredAt]. */
        val LEDGER_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** Escapes the wildcards of a description filter so that they match literally. */
        const val ESCAPE = '\\'
    }
}
