package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

/** Stores users together with their password hashes, so a registration is never half done. */
interface UserCredentialStore {
    /** Atomically registers [user] only when its display name is free. */
    fun register(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration

    /** The credential of the user whose display name is exactly [name], or null when there is none. */
    fun findByName(name: String): StoredCredential?
}

enum class UserRegistration { REGISTERED, NAME_TAKEN }

data class StoredCredential(
    val userId: UserId,
    val passwordHash: PasswordHash,
)
