package com.homeassistant.application.port.input.identity

import com.homeassistant.domain.identity.RegisteredUser

/** Lists registered application users. */
interface UserRegistry {
    fun list(): List<RegisteredUser>

    companion object {
        val NONE: UserRegistry = object : UserRegistry {
            override fun list(): List<RegisteredUser> = emptyList()
        }
    }
}
