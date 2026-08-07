package com.homeassistant.domain.identity

/** Determines whether an application user may access household features. */
fun interface HouseholdAccessPolicy {
    /** Returns whether the user is authorized to access household data. */
    fun isAuthorized(userId: UserId): Boolean
}

private class FixedHouseholdAccessPolicy(
    userIds: Collection<UserId>,
) : HouseholdAccessPolicy {
    private val authorizedUserIds = userIds.toSet()

    override fun isAuthorized(userId: UserId): Boolean =
        userId in authorizedUserIds
}

class HouseholdAccessDeniedException : RuntimeException("household access denied")

object HouseholdAccessPolicies {
    fun fixed(userIds: Collection<UserId>): HouseholdAccessPolicy =
        FixedHouseholdAccessPolicy(userIds)

    fun denyAll(): HouseholdAccessPolicy =
        HouseholdAccessPolicy { false }
}
