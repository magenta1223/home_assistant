package com.homeassistant.domain.identity

data class HouseholdMember(
    val userId: UserId,
    val displayName: String,
) {
    init {
        require(displayName.isNotBlank()) { "displayName is required" }
    }
}
