package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId

class UserRegistryService(
    private val users: UserRepository,
) : UserRegistry, UserAccessPolicy {
    override fun list(): List<RegisteredUser> = users.findAll()

    override fun isAuthorized(userId: UserId): Boolean = users.findById(userId) != null
}
