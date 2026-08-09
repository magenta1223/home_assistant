package com.homeassistant.configuration

object AppConfig {
    const val DEFAULT_PORT              = 8080
    const val DEFAULT_HOST              = "127.0.0.1"
    const val CONFIG_KEY_DB_PATH        = "homeassistant.dbPath"
    const val ENV_VAR_QDRANT_URL            = "QDRANT_URL"
    const val ENV_VAR_QDRANT_COLLECTION     = "QDRANT_COLLECTION"
    const val ENV_VAR_EMBEDDING_MODEL       = "EMBEDDING_MODEL"
    const val ENV_VAR_SLACK_APP_TOKEN       = "SLACK_APP_TOKEN"
    const val ENV_VAR_SLACK_BOT_TOKEN       = "SLACK_BOT_TOKEN"
    const val ENV_VAR_SLACK_TEAM_ID          = "SLACK_TEAM_ID"
    const val ENV_VAR_SLACK_MEMBER_SCOPES_JSON = "SLACK_MEMBER_SCOPES_JSON"
    const val ENV_VAR_HTTP_MEMBER_API_KEYS_JSON = "HTTP_MEMBER_API_KEYS_JSON"
    const val ENV_VAR_CODEX_EXECUTABLE       = "CODEX_EXECUTABLE"
    const val ENV_VAR_CODEX_EXPECTED_VERSION = "CODEX_EXPECTED_VERSION"
    const val ENV_VAR_CODEX_WORK_DIR         = "CODEX_WORK_DIR"
    const val ENV_VAR_CODEX_HOME             = "CODEX_HOME"
    const val ENV_VAR_CODEX_API_KEY          = "CODEX_API_KEY"
    const val ENV_VAR_CODEX_TIMEOUT_SECONDS  = "CODEX_TIMEOUT_SECONDS"
    const val DEFAULT_OLLAMA_HOST           = "127.0.0.1:11435"
    const val DEFAULT_OLLAMA_BASE_URL       = "http://$DEFAULT_OLLAMA_HOST"
    const val DEFAULT_OLLAMA_RUNTIME_DIR    = "runtime/ollama"
    const val DEFAULT_QDRANT_URL            = "http://localhost:6333"
    const val DEFAULT_QDRANT_COLLECTION     = "canonical_memories"
    const val DEFAULT_EMBEDDING_MODEL_NAME  = "qllama/multilingual-e5-base"
    const val DEFAULT_EMBEDDING_VECTOR_SIZE = 768
    const val DEFAULT_CODEX_TIMEOUT_SECONDS = 120L
    const val DEFAULT_DB_PATH           = "db/homeAssistant.sqlite"
    const val ROUTE_HEALTH              = "/health"
    const val ROUTE_KNOWLEDGE_PAGE = "/knowledge"
    const val ROUTE_KNOWLEDGE_USERS = "/api/knowledge/users"
    const val ROUTE_KNOWLEDGE_IMPORT_ANALYZE = "/api/knowledge/import/analyze"
    const val ROUTE_MEMORY_ANSWER = "/api/memories/answer"
}
