package com.homeassistant.core.constants

object AppConfig {
    const val DEFAULT_PORT              = 8080
    const val CONFIG_KEY_DB_PATH        = "homeassistant.dbPath"
    const val CONFIG_KEY_API_KEY        = "homeassistant.anthropicApiKey"
    const val ENV_VAR_API_KEY               = "ANTHROPIC_API_KEY"
    const val ENV_VAR_AI_PROVIDER           = "AI_PROVIDER"
    const val ENV_VAR_OPENROUTER_API_KEY    = "OPENROUTER_API_KEY"
    const val ENV_VAR_OPENROUTER_MODEL      = "OPENROUTER_MODEL"
    const val ENV_VAR_USE_DUMMY_PIPELINE    = "USE_DUMMY_PIPELINE"
    const val DEFAULT_OPENROUTER_MODEL      = "z-ai/glm-4.5-air:free"
    const val DEFAULT_DB_PATH           = "db/homeAssistant.sqlite"
    const val SESSION_TIMEOUT_MINUTES   = 10L
    const val RECENT_LIMIT              = 10
    const val SIMILAR_LIMIT             = 5
    const val CONTEXT_ROWS_SHOWN        = 10
    const val MAX_TOKENS_DATE_PARSE     = 128
    const val MAX_TOKENS_INTENT         = 512
    const val MAX_TOKENS_CHAT           = 512
    const val JDBC_DRIVER               = "org.sqlite.JDBC"
    const val JDBC_URL_PREFIX           = "jdbc:sqlite:"
    const val ROUTE_HEALTH              = "/health"
    const val ROUTE_CHAT                = "/api/chat"
}
