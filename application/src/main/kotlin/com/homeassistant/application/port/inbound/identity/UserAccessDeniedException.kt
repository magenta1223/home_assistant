package com.homeassistant.application.port.inbound.identity

/** The requesting user is not a registered application user and may not use protected features. */
class UserAccessDeniedException internal constructor() : RuntimeException("user access denied")
