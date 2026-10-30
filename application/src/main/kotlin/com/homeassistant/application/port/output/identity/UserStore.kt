package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

/** Reads the registered application users. */
interface UserStore {
    /** Every registered user with a display name, ordered by name ignoring case. */
    fun list(): List<RegisteredUser>

    /** True when [userId] belongs to a registered user with a display name. */
    fun isRegistered(userId: UserId): Boolean
}
