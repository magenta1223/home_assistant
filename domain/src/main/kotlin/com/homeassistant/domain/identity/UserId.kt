package com.homeassistant.domain.identity

@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "userId is required" }
    }
}
