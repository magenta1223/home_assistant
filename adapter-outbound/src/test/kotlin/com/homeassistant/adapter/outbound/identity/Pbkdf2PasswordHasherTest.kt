package com.homeassistant.adapter.outbound.identity

import com.homeassistant.domain.identity.Password
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Pbkdf2PasswordHasherTest {
    @Test
    fun `password hashes are salted and verify only the same password`() {
        val hasher = Pbkdf2PasswordHasher(iterations = 1_000)
        val password = Password("correct-password")

        val hash = hasher.hash(password)

        assertTrue(hasher.matches(password, hash))
        assertFalse(hasher.matches(Password("wrong-password"), hash))
        assertNotEquals(hash, hasher.hash(password))
        assertFalse(hash.encoded.contains(password.value))
    }
}
