package com.homeassistant.domain.finance

import com.homeassistant.domain.identity.UserId

/**
 * How [ownerId] presents one of their payment methods.
 *
 * @property paymentMethod the ledger's name of the account or card, as in [FinanceTransaction.paymentMethod].
 * @property shared whether the members of the owner's household see the expenses paid with it.
 * @property alias the name shown in place of [paymentMethod], such as 식비, or null to show [paymentMethod] itself.
 */
data class PaymentMethodSetting(
    val ownerId: UserId,
    val paymentMethod: String,
    val shared: Boolean,
    val alias: String?,
) {
    init {
        require(paymentMethod.isNotBlank()) { "paymentMethod is required" }
        alias?.let {
            require(it.isNotBlank()) { "alias must not be blank" }
            require(it.length <= MAX_ALIAS_LENGTH) { "alias must be at most $MAX_ALIAS_LENGTH characters" }
        }
    }

    /** The name to show for [paymentMethod]. */
    val displayName: String
        get() = alias ?: paymentMethod

    companion object {
        const val MAX_ALIAS_LENGTH = 30
    }
}
