package com.homeassistant.core.constants

object AppConfig {
    const val DEFAULT_PORT              = 8080
    const val CONFIG_KEY_DB_PATH        = "homeassistant.dbPath"
    const val ENV_VAR_AI_PROVIDER           = "AI_PROVIDER"
    const val ENV_VAR_OPENROUTER_API_KEY    = "OPENROUTER_API_KEY"
    const val ENV_VAR_OPENROUTER_MODEL      = "OPENROUTER_MODEL"
    const val ENV_VAR_OLLAMA_BASE_URL       = "OLLAMA_BASE_URL"
    const val ENV_VAR_OLLAMA_MODEL          = "OLLAMA_MODEL"
    const val ENV_VAR_QDRANT_URL            = "QDRANT_URL"
    const val ENV_VAR_QDRANT_COLLECTION     = "QDRANT_COLLECTION"
    const val ENV_VAR_EMBEDDING_MODEL       = "EMBEDDING_MODEL"
    const val DEFAULT_OPENROUTER_MODEL      = "google/gemini-2.5-flash-lite"
    const val DEFAULT_OLLAMA_BASE_URL       = "http://localhost:11434"
    const val DEFAULT_OLLAMA_MODEL          = "llama3.2"
    const val DEFAULT_QDRANT_URL            = "http://localhost:6333"
    const val DEFAULT_QDRANT_COLLECTION     = "family_memories"
    const val DEFAULT_DB_PATH           = "db/homeAssistant.sqlite"
    const val DEFAULT_LLM_MAX_TOKENS    = 2048
    const val JDBC_DRIVER               = "org.sqlite.JDBC"
    const val JDBC_URL_PREFIX           = "jdbc:sqlite:"
    const val ROUTE_HEALTH              = "/health"
    const val ROUTE_KAKAO_IMPORT_ANALYZE = "/api/kakao/import/analyze"
    const val ROUTE_TEST_TOPIC_ANALYSIS_KAKAO_SMALL_SET = "/api/test/topic-analysis/kakao-small-set"
}
