package com.homeassistant.domain.identity

/** Determines whether an application user may access protected features. */
fun interface UserAccessPolicy {
    /** Returns whether the user is authorized to access protected data. */
    fun isAuthorized(userId: UserId): Boolean
}

class UserAccessDeniedException : RuntimeException("user access denied")
