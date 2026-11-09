package com.homeassistant.application.port.inbound.contexttool

/** The context token of a tool call is unknown, revoked or expired, so no user's context may be read with it. */
class ContextAccessExpiredException internal constructor() : RuntimeException("context access expired")
