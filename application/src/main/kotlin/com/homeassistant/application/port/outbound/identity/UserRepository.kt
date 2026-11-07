package com.homeassistant.application.port.outbound.identity

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

/** Stores users together with their password hashes, so a registration is never half done. */
interface UserRepository {
    /**
     * Every user with a display name, ordered by name ignoring case. It has no condition: the audience picker offers
     * every user an episode can be shared with.
     */
    fun findAll(): List<RegisteredUser>

    /** The user with [userId], or null when no user with a display name has it. */
    fun findById(userId: UserId): RegisteredUser?

    /** The credential of the user whose display name is [name], or null. */
    fun findCredentialByName(name: String): StoredCredential?

    /**
     * Stores the user and its password hash in one write and returns the user.
     * @throws UserNameConflict when another user already has the display name.
     */
    fun save(registration: UserRegistration): RegisteredUser
}

data class UserRegistration(
    val user: RegisteredUser,
    val passwordHash: PasswordHash,
)

/** A [UserRegistration] whose display name another user already has. */
class UserNameConflict(displayName: String) : IllegalStateException("display name is taken: $displayName")

data class StoredCredential(
    val userId: UserId,
    val passwordHash: PasswordHash,
)
