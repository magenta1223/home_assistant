package com.homeassistant.feature.finance.port.outbound

import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.UserId

/** Stores how each user presents their payment methods. */
interface PaymentMethodSettingRepository {
    /**
     * Every payment method [userId] has paid or received with, ordered by name, with its stored setting; one without
     * a stored setting is not shared and has no alias.
     */
    fun findPaymentMethodsOf(userId: UserId): List<PaymentMethodSetting>

    /** The stored settings of the payment methods of [ownerIds]. */
    fun findSettingsOf(ownerIds: Set<UserId>): List<PaymentMethodSetting>

    /** Stores [settings], replacing the stored setting of the same owner and payment method, and returns them. */
    fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting>
}
