package com.homeassistant.application.port.outbound.identity

import com.homeassistant.domain.identity.Password

/** Derives and verifies stored password hashes. */
interface PasswordHasher {
    fun hash(password: Password): PasswordHash

    fun matches(password: Password, hash: PasswordHash): Boolean
}

