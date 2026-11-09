package com.homeassistant.domain.finance

import com.homeassistant.domain.identity.UserId
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * One transaction from a household finance ledger, owned by [ownerId].
 *
 * @property occurredAt when the transaction happened, in Korea Standard Time.
 * @property type the ledger's transaction type, such as expense, income or transfer.
 * @property majorCategory the ledger's top-level category.
 * @property minorCategory the ledger's category under [majorCategory].
 * @property description what the transaction was for, usually the merchant.
 * @property amount the signed amount in the smallest unit of [currency]; a cancellation arrives as a positive amount.
 * @property currency the currency of [amount].
 * @property paymentMethod the account or card that paid or received [amount].
 * @property memo the owner's note, or null when there is none.
 */
data class FinanceTransaction(
    val ownerId: UserId,
    val occurredAt: LocalDateTime,
    val type: String,
    val majorCategory: String,
    val minorCategory: String,
    val description: String,
    val amount: Long,
    val currency: String,
    val paymentMethod: String,
    val memo: String?,
) {
    /**
     * Identifies this transaction among its owner's transactions across re-imports. It hashes only what the ledger
     * never lets the owner change — time to the second, description, amount, currency and payment method — so editing
     * the type, a category or the memo keeps the key.
     */
    val transactionKey: String = sha256(
        listOf(canonicalTime(occurredAt), description, amount.toString(), currency, paymentMethod)
            .joinToString(FIELD_SEPARATOR),
    )

    private companion object {
        const val FIELD_SEPARATOR = "\u001F"

        fun canonicalTime(time: LocalDateTime): String =
            "%04d-%02d-%02dT%02d:%02d:%02d".format(
                time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second,
            )

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
