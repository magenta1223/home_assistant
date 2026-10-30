package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.Password

/** Derives and verifies stored password hashes. */
interface PasswordHasher {
    fun hash(password: Password): PasswordHash

    fun matches(password: Password, hash: PasswordHash): Boolean
}

/** An opaque hash whose encoding, including its salt and cost, belongs to the [PasswordHasher]. */
@JvmInline
value class PasswordHash(val encoded: String)
