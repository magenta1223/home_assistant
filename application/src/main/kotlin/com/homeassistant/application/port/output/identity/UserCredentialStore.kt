package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

/** Stores users together with their password hashes, so a registration is never half done. */
interface UserCredentialStore {
    fun hasUsers(): Boolean

    /** Atomically registers [user] only when no user exists. */
    fun registerFirst(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration

    /** Atomically registers [user] only when its display name is free. */
    fun register(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration

    fun findByName(name: String): StoredCredential?
}

enum class UserRegistration { REGISTERED, USERS_ALREADY_EXIST, NAME_TAKEN }

data class StoredCredential(
    val userId: UserId,
    val passwordHash: PasswordHash,
)
