package com.homeassistant.application.port.outbound.identity

import com.homeassistant.domain.identity.Password

/** Derives and verifies stored password hashes. */
interface PasswordHasher {
    /** A new salted hash of [password]; hashing the same password twice gives different hashes. */
    fun hash(password: Password): PasswordHash

    /** True when [password] is the one [hash] was derived from. */
    fun matches(password: Password, hash: PasswordHash): Boolean
}

