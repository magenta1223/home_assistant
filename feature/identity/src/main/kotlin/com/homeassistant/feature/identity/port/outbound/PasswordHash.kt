package com.homeassistant.feature.identity.port.outbound

/** An opaque hash whose encoding, including its salt and cost, belongs to the [PasswordHasher]. */
@JvmInline
value class PasswordHash(val encoded: String)