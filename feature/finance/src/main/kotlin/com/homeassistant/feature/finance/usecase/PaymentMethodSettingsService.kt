package com.homeassistant.feature.finance.usecase

import com.homeassistant.application.port.inbound.finance.PaymentMethodSettings
import com.homeassistant.application.port.inbound.finance.UnknownPaymentMethodException
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.PaymentMethodSettingRepository

/** Keeps, in [repository], which payment methods each user shares with their household and what they call them. */
class PaymentMethodSettingsService(
    private val repository: PaymentMethodSettingRepository,
) : PaymentMethodSettings {

    override fun listOf(userId: UserId): List<PaymentMethodSetting> = repository.findPaymentMethodsOf(userId)

    override fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting> {
        val usedByOwner = settings.map(PaymentMethodSetting::ownerId).distinct().associateWith(::paymentMethodsOf)
        settings.firstOrNull { it.paymentMethod !in usedByOwner.getValue(it.ownerId) }
            ?.let { throw UnknownPaymentMethodException(it.paymentMethod) }
        return repository.save(settings)
    }

    private fun paymentMethodsOf(ownerId: UserId): Set<String> =
        repository.findPaymentMethodsOf(ownerId).mapTo(hashSetOf(), PaymentMethodSetting::paymentMethod)
}
