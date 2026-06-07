# CLAUDE.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run the server (port 8080)
./gradlew :app:run

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :core:test
./gradlew :nlp:test
./gradlew :domain:test
./gradlew :app:test
```

## Environment Setup

Copy `.env` to the project root (already present; gitignored). Key variables:

| Variable | Default | Notes |
|---|---|---|
| `AI_PROVIDER` | `ollama` | `ollama` or `openrouter` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Used when `AI_PROVIDER=ollama` |
| `OLLAMA_MODEL` | `llama3.2` | |
| `OPENROUTER_API_KEY` | - | Required when `AI_PROVIDER=openrouter` |
| `OPENROUTER_MODEL` | `z-ai/glm-4.5-air:free` | |
| `EMBEDDING_MODEL` | - | Required only when wiring embedding-backed memory features |
| `QDRANT_URL` | `http://localhost:6333` | Required only when wiring vector search |
| `QDRANT_COLLECTION` | `family_memories` | |

Server port and DB path are configured in `AppConfig` and Ktor application config.

## Project Direction

The project is now a **home second brain**, not a general chat assistant. The primary flow is:

1. Import source records, currently Kakao exports.
2. Analyze source records into topic candidates with an LLM.
3. Store pending memory candidates and evidence.
4. Approve, reject, search, and retrieve memories through domain tools or future purpose-built APIs.

Do not reintroduce `/api/chat`, conversation sessions, intent-analysis pipelines, or chat-response DTOs unless the product direction changes again.

## Module Architecture

```text
core/       - shared domain types, LLM interfaces, tool schemas, constants
nlp/        - LLM backends and source/topic analysis
domain/     - DB schema, Kakao import, memory repositories/tools/vector store
app/        - Ktor server wiring and HTTP routes
```

Dependency graph: `app` -> `nlp` + `domain` -> `core`

### core

Pure abstractions and shared types.

- `nlp/LlmBackend` - provider-independent LLM completion interface.
- `nlp/Message`, `MessageRole`, `LlmTypes` - LLM request/response domain types.
- `tools/*` - JSON Schema based tool definitions and executor interface.
- `identity/UserId` - user identity value type used by memory tools.
- `memory/MemoryTypes.kt` - memory categories and candidate status.
- `constants/AppConfig`, `Env` - configuration keys and defaults.

### nlp

- `backend/` - Ollama, OpenRouter, and Anthropic backend implementations.
- `backend/utils/` - tool prompt injection and prompt-injection tool-call parsing.
- `analysis/TopicAnalysisService` - turns source documents into validated topic candidates and stores them through `TopicAnalysisRepository`.

### domain

- `db/` - database initialization and Exposed table definitions.
- `kakao/` - Kakao export parsing, import, and persistence.
- `memory/` - memory candidate/repository logic, tools, embeddings, and vector store.
- `DomainToolRegistry` - single registration point for domain tools.

### app

Ktor + Netty server. Current routes:

- `GET /health` -> `{"status":"ok"}`
- `POST /api/kakao/import/analyze` -> imports Kakao text/file content and returns pending topic candidates.

`Application.kt` wires the database, selected LLM backend, Kakao import service, topic analysis service, and routes.

## Key Design Patterns

- Source-first analysis: analyze imported records as source documents before creating durable memories.
- Evidence-backed memory: topic candidates must preserve source references so accepted memories are traceable.
- Tool registration from one source: `DomainToolRegistry` derives execution dispatch from registered tool groups.
- LLM backend reuse: source analysis uses `LlmBackend` directly; avoid wrapping it in chat-specific client layers.

## Coding Principles

### 1. LLM responses are parsed via `@Serializable` DTO

Every JSON response from an LLM backend must be deserialized with a dedicated `@Serializable` data class and `Json.decodeFromString<T>()`. Raw `JsonElement` / `.jsonObject` / `.jsonPrimitive` extraction is not allowed.

```kotlin
// BAD
val obj = Json.parseToJsonElement(text).jsonObject
val intent = obj["intent"]?.jsonPrimitive?.content

// GOOD
@Serializable
data class TopicLlmResponse(val title: String, val evidenceRecordIds: List<String>)
val response = Json.decodeFromString<TopicLlmResponse>(text)
```

Parsing errors must surface via try/catch returning or throwing a typed fallback, not silent null-chains.

### 2. Interfaces declare domain types only

Interface method signatures and properties must not use primitive values, collections of primitives, or kotlinx JSON node types. Wrap raw values in domain types before they cross an interface boundary.

```kotlin
// BAD
interface LlmBackend {
    suspend fun complete(system: String): String
}

// GOOD
@JvmInline value class SystemPrompt(val value: String)
@JvmInline value class LlmRawResponse(val value: String)

interface LlmBackend {
    suspend fun complete(system: SystemPrompt): LlmResponse
}
```

### 3. Be concise

No redundant conditions, no defensive null-checks for values that cannot be null, and no wrapper functions that only delegate. Write exactly what the logic requires.

### 4. Make illegal states unrepresentable

Use the type system and sealed exhaustiveness where possible. Prefer compiler-enforced cases over `else` branches that silently swallow new variants.

When dispatching tools, derive routing from a single registration source rather than maintaining parallel `tools()` and `execute()` structures.
