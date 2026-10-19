package com.homeassistant.domain.identity

data class RegisteredUser(
    val userId: UserId,
    val displayName: String,
) {
    init {
        require(displayName.isNotBlank()) { "displayName is required" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be at most $MAX_DISPLAY_NAME_LENGTH characters"
        }
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 50
    }
}
