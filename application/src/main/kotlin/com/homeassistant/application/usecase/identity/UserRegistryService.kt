package com.homeassistant.application.usecase.identity

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import java.util.UUID

class UserRegistryService(
    private val store: UserStore,
    private val newUserId: () -> UserId = { UserId("member-${UUID.randomUUID()}") },
    private val clock: () -> Long = System::currentTimeMillis,
) : UserRegistry, UserAccessPolicy {
    override fun find(identity: ConversationIdentity): RegisteredUser? = store.find(identity)

    override fun register(request: RegisterUserRequest): RegisteredUser {
        val displayName = RegisteredUser.normalizeDisplayName(request.displayName)
        return store.register(request.identity, newUserId(), displayName, clock())
    }

    override fun list(): List<RegisteredUser> = store.list()

    override fun isAuthorized(userId: UserId): Boolean = store.isRegistered(userId)

    fun reserveLegacy(identity: ConversationIdentity, userId: UserId) {
        store.reserve(identity, userId, clock())
    }
}
