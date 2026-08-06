package com.homeassistant.domain.identity

@Deprecated("The application has one household; use UserId directly")
@JvmInline
value class FamilyId(val value: String)

@Deprecated("The application has one household; use UserId directly")
data class HouseholdAccessScope(
    val userId: UserId,
    val familyId: FamilyId,
)

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
