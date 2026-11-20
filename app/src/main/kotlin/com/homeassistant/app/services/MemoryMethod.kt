package com.homeassistant.app.services

import com.homeassistant.configuration.AppConfig

/** How stored source records are memorized and searched; chosen once at startup from [AppConfig.ENV_VAR_MEMORY_METHOD]. */
internal enum class MemoryMethod {
    /** Episodes extracted from source records, organized into Components and Groups ([XMemoryModule]). */
    XMEMORY,
}
