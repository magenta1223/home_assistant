package com.homeassistant.core.identity

@JvmInline
value class FamilyId(val value: String) {
    init {
        require(value.isNotBlank()) { "familyId is required" }
    }
}

data class HouseholdAccessScope(
    val userId: UserId,
    val familyId: FamilyId,
) {
    init {
        require(userId.value.isNotBlank()) { "userId is required" }
    }
}

fun interface HouseholdAccessPolicy {
    fun isAuthorized(scope: HouseholdAccessScope): Boolean
}

class FixedHouseholdAccessPolicy(
    scopes: Collection<HouseholdAccessScope>,
) : HouseholdAccessPolicy {
    private val authorizedScopes = scopes.toSet()

    override fun isAuthorized(scope: HouseholdAccessScope): Boolean =
        scope in authorizedScopes
}

class HouseholdAccessDeniedException : RuntimeException("household access denied")
