package com.homeassistant.application.usecase.contexttool

import com.homeassistant.domain.identity.UserId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ContextGrantsTest {
    @Test
    fun `an issued token stands for its user until it is revoked`() {
        val grants = ContextGrants(LIFETIME, AdjustableClock(NOW))
        val member = grants.issue(MEMBER)
        val other = grants.issue(OTHER)

        assertEquals(MEMBER, grants.ownerOf(member))
        assertEquals(OTHER, grants.ownerOf(other))
        assertNotEquals(member, other)

        grants.revoke(member)

        assertNull(grants.ownerOf(member))
        assertEquals(OTHER, grants.ownerOf(other))
    }

    @Test
    fun `a token stops standing for its user once its lifetime has passed`() {
        val clock = AdjustableClock(NOW)
        val grants = ContextGrants(LIFETIME, clock)
        val token = grants.issue(MEMBER)

        clock.millis = NOW + LIFETIME.toMillis() - 1
        assertEquals(MEMBER, grants.ownerOf(token))

        clock.millis = NOW + LIFETIME.toMillis()
        assertNull(grants.ownerOf(token))
    }

    @Test
    fun `a token that was never issued stands for no one`() {
        assertNull(ContextGrants(LIFETIME).ownerOf("unknown"))
    }

    /** A clock the test moves forward. */
    private class AdjustableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private companion object {
        val MEMBER = UserId("member-1")
        val OTHER = UserId("member-2")
        val LIFETIME: Duration = Duration.ofMinutes(10)
        const val NOW = 1_000_000L
    }
}
