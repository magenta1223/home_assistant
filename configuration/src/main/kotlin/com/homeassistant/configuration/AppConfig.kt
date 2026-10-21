package com.homeassistant.configuration

object AppConfig {
    const val DEFAULT_PORT              = 8080
    const val DEFAULT_HOST              = "127.0.0.1"
    const val CONFIG_KEY_DB_PATH        = "homeassistant.dbPath"
    const val ENV_VAR_EMBEDDING_MODEL       = "EMBEDDING_MODEL"
    const val ENV_VAR_HTTP_MEMBER_API_KEYS_JSON = "HTTP_MEMBER_API_KEYS_JSON"
    const val ENV_VAR_AGENT_BACKEND         = "AGENT_BACKEND"
    const val ENV_VAR_AGENT_TIMEOUT_SECONDS = "AGENT_TIMEOUT_SECONDS"
    const val DEFAULT_OLLAMA_HOST           = "127.0.0.1:11435"
    const val DEFAULT_OLLAMA_BASE_URL       = "http://$DEFAULT_OLLAMA_HOST"
    const val DEFAULT_OLLAMA_RUNTIME_DIR    = "runtime/ollama"
    const val DEFAULT_EMBEDDING_MODEL_NAME  = "qllama/multilingual-e5-base"
    const val DEFAULT_EMBEDDING_VECTOR_SIZE = 768
    const val DEFAULT_AGENT_BACKEND         = "claude"
    const val DEFAULT_AGENT_TIMEOUT_SECONDS = 600L
    const val DEFAULT_DB_PATH           = "db/homeAssistant.sqlite"
    const val ROUTE_HEALTH              = "/health"
    const val ROUTE_KNOWLEDGE_PAGE = "/knowledge"
    const val ROUTE_MEMORY_CONVERSATION_PAGE = "/conversation"
    const val ROUTE_KNOWLEDGE_USERS = "/api/knowledge/users"
    const val ROUTE_KNOWLEDGE_IMPORT_ANALYZE = "/api/knowledge/import/analyze"
    const val ROUTE_MEMORY_CONVERSATION = "/api/memory/conversation"
}
