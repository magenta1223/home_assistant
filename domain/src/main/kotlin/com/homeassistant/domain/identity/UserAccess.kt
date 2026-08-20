package com.homeassistant.domain.identity

/** Determines whether an application user may access protected features. */
fun interface UserAccessPolicy {
    /** Returns whether the user is authorized to access protected data. */
    fun isAuthorized(userId: UserId): Boolean
}

private class FixedUserAccessPolicy(
    userIds: Collection<UserId>,
) : UserAccessPolicy {
    private val authorizedUserIds = userIds.toSet()

    override fun isAuthorized(userId: UserId): Boolean =
        userId in authorizedUserIds
}

class UserAccessDeniedException : RuntimeException("user access denied")

object UserAccessPolicies {
    fun fixed(userIds: Collection<UserId>): UserAccessPolicy =
        FixedUserAccessPolicy(userIds)

    fun denyAll(): UserAccessPolicy =
        UserAccessPolicy { false }
}
