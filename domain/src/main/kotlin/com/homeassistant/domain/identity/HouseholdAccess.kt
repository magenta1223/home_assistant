package com.homeassistant.domain.identity

fun interface HouseholdAccessPolicy {
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
