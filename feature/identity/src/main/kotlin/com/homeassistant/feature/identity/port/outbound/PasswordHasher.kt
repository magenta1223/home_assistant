package com.homeassistant.feature.identity.port.outbound

import com.homeassistant.domain.identity.Password

/** Derives and verifies stored password hashes. */
interface PasswordHasher {
    fun hash(password: Password): PasswordHash

    fun matches(password: Password, hash: PasswordHash): Boolean
}

