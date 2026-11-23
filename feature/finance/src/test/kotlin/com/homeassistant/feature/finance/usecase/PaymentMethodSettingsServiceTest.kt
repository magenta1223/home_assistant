package com.homeassistant.feature.finance.usecase

import com.homeassistant.application.port.inbound.finance.UnknownPaymentMethodException
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.PaymentMethodSettingRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaymentMethodSettingsServiceTest {
    private val repository = InMemorySettings(mapOf(OWNER to listOf("신용카드", "체크카드")))
    private val service = PaymentMethodSettingsService(repository)

    @Test
    fun `settings of payment methods the owner used are stored and listed`() {
        val foodCard = PaymentMethodSetting(OWNER, "신용카드", shared = true, alias = "식비")

        assertEquals(listOf(foodCard), service.save(listOf(foodCard)))
        assertEquals(
            listOf(foodCard, PaymentMethodSetting(OWNER, "체크카드", shared = false, alias = null)),
            service.listOf(OWNER),
        )
    }

    @Test
    fun `a payment method the owner never used is refused and nothing is stored`() {
        val settings = listOf(
            PaymentMethodSetting(OWNER, "신용카드", shared = true, alias = null),
            PaymentMethodSetting(OWNER, "남의카드", shared = true, alias = null),
        )

        assertFailsWith<UnknownPaymentMethodException> { service.save(settings) }
        assertTrue(repository.stored.isEmpty())
    }

    /** Knows which payment methods each owner used and keeps their stored settings. */
    private class InMemorySettings(private val used: Map<UserId, List<String>>) : PaymentMethodSettingRepository {
        val stored = linkedMapOf<Pair<UserId, String>, PaymentMethodSetting>()

        override fun findPaymentMethodsOf(userId: UserId): List<PaymentMethodSetting> =
            used[userId].orEmpty().map { method ->
                stored[userId to method] ?: PaymentMethodSetting(userId, method, shared = false, alias = null)
            }

        override fun findSettingsOf(ownerIds: Set<UserId>): List<PaymentMethodSetting> =
            stored.values.filter { it.ownerId in ownerIds }

        override fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting> {
            settings.forEach { stored[it.ownerId to it.paymentMethod] = it }
            return settings
        }
    }

    private companion object {
        val OWNER = UserId("member-1")
    }
}
