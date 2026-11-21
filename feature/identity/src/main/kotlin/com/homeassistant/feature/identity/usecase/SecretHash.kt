package com.homeassistant.feature.identity.usecase

import java.security.MessageDigest

/** The SHA-256 hex digest of [secret], which is what gets stored in place of a session id or invitation code. */
internal fun secretHash(secret: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
