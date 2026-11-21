package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.identity.Pbkdf2PasswordHasher
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.feature.identity.usecase.HouseholdService
import com.homeassistant.feature.identity.usecase.UserAuthenticationService
import com.homeassistant.feature.identity.usecase.UserRegistryService

/** Assembles user registration, authentication, and the access policy other modules check viewers against. */
internal class IdentityModule(repositories: RepositoryStores) {
    /** Registered users; also the access policy for knowledge viewers. */
    val users = UserRegistryService(repositories.users)

    /** Registers people and keeps them logged in with sessions. */
    val authentication = UserAuthenticationService(
        repositories.users,
        repositories.userSessions,
        Pbkdf2PasswordHasher(),
    )

    /** Forms households by invitation. */
    val households = HouseholdService(repositories.households)
}
