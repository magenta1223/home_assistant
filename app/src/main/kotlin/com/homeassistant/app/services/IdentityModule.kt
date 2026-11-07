package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.identity.Pbkdf2PasswordHasher
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.usecase.identity.UserAuthenticationService
import com.homeassistant.application.usecase.identity.UserRegistryService

/** Assembles user registration, authentication, and the access policy other modules check viewers against. */
internal class IdentityModule(repositories: RepositoryStores) {
    /** Registered users; also the access policy for knowledge viewers. */
    val users = UserRegistryService(repositories.users)

    /** Registers people and keeps them logged in with sessions. */
    val authentication = UserAuthenticationService(
        repositories.userCredentials,
        repositories.userSessions,
        Pbkdf2PasswordHasher(),
    )
}
