package com.homeassistant.application.port.output.identity

import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId

interface UserStore {
    fun list(): List<RegisteredUser>

    fun isRegistered(userId: UserId): Boolean
}
