# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

# Run the Flask test client (Python, proxies to localhost:8080)
./gradlew runTestClient          # or: python scripts/test-client/app.py
```

## Environment Setup

Copy `.env` to the project root (already present; gitignored). Key variables:

| Variable | Default | Notes |
|---|---|---|
| `AI_PROVIDER` | `ollama` | `ollama` or `openrouter` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `OLLAMA_MODEL` | `llama3.2` | |
| `OPENROUTER_API_KEY` | — | Required if `AI_PROVIDER=openrouter` |
| `OPENROUTER_MODEL` | `z-ai/glm-4.5-air:free` | |
| `USE_DUMMY_PIPELINE` | `false` | Set `true` to skip LLM calls entirely |

Server port and DB path are in `app/src/main/resources/application.yaml`.

## Module Architecture

```
core/       — interfaces, models, constants (no external I/O)
nlp/        — implements core interfaces; LLM backends + pipeline
domain/     — (in progress) DB schema, commands, context retrieval
app/        — Ktor server wiring (entry point: Application.kt)
```

**Dependency graph:** `app` → `nlp` + `domain` → `core`

### core

Pure abstractions — no I/O allowed here.

- `nlp/AiClient` — intent analysis + chat session interface
- `nlp/LlmBackend` — single-method `complete(system, messages, config): String?`
- `nlp/PromptConfig` — system-prompt strings
- `session/SessionManager` — Caffeine-backed in-memory conversation history, keyed by `SessionKey(platform, conversationId)`; TTL = 10 min
- `models/ApiModels.kt` — all shared data classes (`ChatRequest`, `ChatResponse`, `Message`, `IntentAnalysis`, `NlpChatResponse`, etc.)
- `constants/AppConfig` — all magic strings and defaults in one place

### nlp

- **Backends** (`backend/`): `OllamaBackend`, `OpenRouterBackend`, `AnthropicBackend` — each implements `LlmBackend` via Ktor HTTP client or Anthropic SDK. Selected by `LmBackendFactory` based on `AI_PROVIDER` env var.
- **`AiClientImpl`** — implements `AiClient` on top of any `LlmBackend`. Parses JSON responses from the LLM for both `analyzeIntent` (returns `IntentAnalysis`) and `chatSession` (returns `NlpChatResponse`).
- **`NliPromptConfig`** — Korean-language system prompts for intent analysis and chat. Intent list is driven by the `Intent` enum.
- **Pipeline** (`pipeline/`):
  - `IChatPipeline.process(ChatRequest): ChatResponse`
  - `ChatPipeline` — full flow: session load → `aiClient.chatSession()` → session save → if `type=result`, run `commandExecutor.execute()` then reset session
  - `DummyChatPipeline` — returns static response, bypasses all LLM calls
  - `NoOpCommandExecutor` — placeholder for `ICommandExecutor`
- **`AiClientFactory`** — creates `AiClientImpl` using `LmBackendFactory`

### app

Ktor + Netty server. Two routes:
- `GET /health` → `{"status":"ok"}`
- `POST /api/chat` → `ChatRequest` → `ChatPipeline.process()` → `ChatResponse`

Wiring in `Application.kt`: reads env/config → builds `AiClient` → builds `Pipeline` → mounts routes.

## Key Design Patterns

- **`type` field on responses**: LLM always returns one of `question` / `result` / `unknown` / `error` (see `ChatResponseType`). The pipeline uses this to decide whether to execute a command or keep the conversation going.
- **Two-phase NLP**: intent analysis (future use for DB context retrieval) is separate from the chat session call. `AiClientImpl.analyzeIntent()` is wired but `ContextRetriever` / `CommandExecutor` integration in `Application.kt` is still TODO.
- **Domain module**: currently empty skeleton — planned to hold Exposed ORM table definitions, command implementations, and embedding-based context retrieval.

## Coding Principles

### 1. LLM responses are parsed via `@Serializable` DTO

Every JSON response from an LLM backend must be deserialized with a dedicated `@Serializable` data class and `Json.decodeFromString<T>()`. Raw `JsonElement` / `.jsonObject` / `.jsonPrimitive` extraction is not allowed.

```kotlin
// BAD
val obj = Json.parseToJsonElement(text).jsonObject
val intent = obj["intent"]?.jsonPrimitive?.content

// GOOD
@Serializable
data class IntentLlmResponse(val intent: String, val contexts: List<ContextLlmSpec>)
val response = Json.decodeFromString<IntentLlmResponse>(text)
```

Parsing errors must surface via try/catch returning a typed fallback — not silent null-chains.

### 2. Interfaces declare domain types only

Interface method signatures and properties must not use:
- Primitive types (`String`, `Int`, `Boolean`, `Double`, …)
- Collections of primitives (`List<String>`, `Set<Int>`, …)
- `JsonElement` or any kotlinx-json node type

Wrap raw values in a domain type before they cross an interface boundary.

```kotlin
// BAD
interface LlmBackend {
    suspend fun complete(system: String, messages: List<Message>, config: LlmConfig): String?
}

// GOOD
@JvmInline value class SystemPrompt(val value: String)
@JvmInline value class LlmRawResponse(val value: String)

interface LlmBackend {
    suspend fun complete(system: SystemPrompt, messages: List<Message>, config: LlmConfig): LlmRawResponse?
}
```

### 3. Be concise — no filler, straight to the point

No redundant conditions, no defensive null-checks for values that can't be null, no wrapper functions that just delegate. Write exactly what the logic requires and nothing else.

### 4. Make illegal states unrepresentable

Use the type system and structure to make missing-case bugs impossible to compile, not just wrong at runtime. When adding a new variant requires manually updating N separate locations, one missed update silently corrupts behavior.

**Prefer sealed exhaustiveness over `else`-guarded dispatch:**

```kotlin
// BAD — else silently swallows unhandled variants
fun handle(r: LlmResponse): String = when (r) {
    is LlmResponse.Text -> r.content.value
    else -> ""
}

// GOOD — compiler enforces every case
fun handle(r: LlmResponse): String = when (r) {
    is LlmResponse.Text -> r.content.value
    is LlmResponse.ToolCall -> r.spec.name.value
}
```

**Derive dispatch from a single registration source instead of maintaining parallel structures:**

```kotlin
// BAD — tools() and execute() must be kept in sync manually
fun tools() = memoTools.tools + todoTools.tools
override suspend fun execute(...) = when {
    spec.name.value.startsWith("memo_") -> memoTools.execute(spec)
    spec.name.value.startsWith("todo_") -> todoTools.execute(spec)
    else -> ToolResult("ERROR: ...")
}

// GOOD — one registration point; routing is derived
private val dispatch = groups.flatMap { g -> g.tools.map { it.name to g } }.toMap()
fun tools() = groups.flatMap { it.tools }
override suspend fun execute(...) =
    dispatch[spec.name]?.execute(spec) ?: error("Unhandled tool: ${spec.name.value}")
```

When an `else` branch is unavoidable (e.g. external input), throw instead of returning a silent error value so the gap is immediately visible.
