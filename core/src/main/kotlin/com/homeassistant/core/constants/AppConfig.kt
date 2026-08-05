package com.homeassistant.core.constants

object AppConfig {
    const val DEFAULT_PORT              = 8080
    const val CONFIG_KEY_DB_PATH        = "homeassistant.dbPath"
    const val ENV_VAR_OLLAMA_BASE_URL       = "OLLAMA_BASE_URL"
    const val ENV_VAR_QDRANT_URL            = "QDRANT_URL"
    const val ENV_VAR_QDRANT_COLLECTION     = "QDRANT_COLLECTION"
    const val ENV_VAR_EMBEDDING_MODEL       = "EMBEDDING_MODEL"
    const val ENV_VAR_SLACK_APP_TOKEN       = "SLACK_APP_TOKEN"
    const val ENV_VAR_SLACK_BOT_TOKEN       = "SLACK_BOT_TOKEN"
    const val ENV_VAR_SLACK_MAX_FILE_SIZE_BYTES = "SLACK_MAX_FILE_SIZE_BYTES"
    const val ENV_VAR_SLACK_TEAM_ID          = "SLACK_TEAM_ID"
    const val ENV_VAR_SLACK_MEMBER_SCOPES_JSON = "SLACK_MEMBER_SCOPES_JSON"
    const val ENV_VAR_CODEX_EXECUTABLE       = "CODEX_EXECUTABLE"
    const val ENV_VAR_CODEX_EXPECTED_VERSION = "CODEX_EXPECTED_VERSION"
    const val ENV_VAR_CODEX_WORK_DIR         = "CODEX_WORK_DIR"
    const val ENV_VAR_CODEX_HOME             = "CODEX_HOME"
    const val ENV_VAR_CODEX_API_KEY          = "CODEX_API_KEY"
    const val ENV_VAR_CODEX_TIMEOUT_SECONDS  = "CODEX_TIMEOUT_SECONDS"
    const val DEFAULT_OLLAMA_BASE_URL       = "http://localhost:11434"
    const val DEFAULT_QDRANT_URL            = "http://localhost:6333"
    const val DEFAULT_QDRANT_COLLECTION     = "family_memories"
    const val DEFAULT_EMBEDDING_MODEL_NAME  = "qllama/multilingual-e5-base"
    const val DEFAULT_EMBEDDING_VECTOR_SIZE = 768
    const val DEFAULT_SLACK_MAX_FILE_SIZE_BYTES = 10_485_760L
    const val DEFAULT_CODEX_TIMEOUT_SECONDS = 120L
    const val DEFAULT_DB_PATH           = "db/homeAssistant.sqlite"
    const val DEFAULT_LLM_MAX_TOKENS    = 8192
    const val JDBC_DRIVER               = "org.sqlite.JDBC"
    const val JDBC_URL_PREFIX           = "jdbc:sqlite:"
    const val ROUTE_HEALTH              = "/health"
    const val ROUTE_KAKAO_IMPORT_ANALYZE = "/api/kakao/import/analyze"
    const val ROUTE_KAKAO_IMPORT_SAVE = "/api/kakao/import/save"
    const val ROUTE_TOPIC_ANSWER = "/api/topics/answer"
}
