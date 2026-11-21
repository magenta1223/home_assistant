package com.homeassistant.application.port.inbound.identity

/** The requesting user is not a registered application user and may not use protected features. */
class UserAccessDeniedException : RuntimeException("user access denied")
