package com.homeassistant.application.port.inbound.finance

import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.UserId

/** Lets users choose which of their payment methods their household sees, and name them. */
interface PaymentMethodSettings {
    /**
     * Every payment method [userId] has paid or received with, ordered by name, with its setting; one never set is not
     * shared and has no alias.
     */
    fun listOf(userId: UserId): List<PaymentMethodSetting>

    /**
     * Stores [settings] and returns them.
     *
     * @throws UnknownPaymentMethodException when a setting names a payment method its owner has never used
     */
    fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting>
}

class UnknownPaymentMethodException(paymentMethod: String) :
    RuntimeException("the payment method is not one of the user's: $paymentMethod")
