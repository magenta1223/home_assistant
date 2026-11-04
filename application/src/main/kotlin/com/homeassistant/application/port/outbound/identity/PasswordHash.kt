package com.homeassistant.application.port.outbound.identity

/** An opaque hash whose encoding, including its salt and cost, belongs to the [PasswordHasher]. */
@JvmInline
value class PasswordHash(val encoded: String)