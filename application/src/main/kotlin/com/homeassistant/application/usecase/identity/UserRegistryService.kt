package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.outbound.identity.UserStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId

class UserRegistryService(
    private val store: UserStore,
) : UserRegistry, UserAccessPolicy {
    override fun list(): List<RegisteredUser> = store.list()

    override fun isAuthorized(userId: UserId): Boolean = store.isRegistered(userId)
}
