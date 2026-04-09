# Tool-Calling Framework Design

**Date:** 2026-04-09  
**Status:** Approved

## Context

`AiClient` needs to invoke real capabilities (asset lookup, grocery management, home status, todo, schedule, news, recipes, memos). The current `command`/`params` string pattern is untyped and doesn't send tool definitions to the LLM. This design replaces it with a proper JSON Schema–based tool-calling framework.

**Scope:** Infrastructure only — `Tool` interface, schema types, LLM delivery, pipeline wiring. Actual tool implementations come later.

---

## Decisions

| Question | Decision |
|---|---|
| Execution model | One-shot (LLM decides tool → execute → result back to LLM → final response) |
| Tool delivery | Backend-specific: Anthropic uses native `tools` param; Ollama/OpenRouter use prompt injection |
| Tool location | `core` module (interface + schema types); implementations live in `domain` |
| Tool result handling | Tool result is passed back to LLM for natural language response generation |

---

## Architecture

### Data Flow

```
ChatRequest
  ↓
ChatPipeline: load session + append user message
  ↓
AiClientImpl.chatSession(messages)  [tools configured at construction]
  ↓
LlmBackend.complete(system, messages, tools) → LlmResponse
  ↓
  ├─ LlmResponse.Text → NlpChatResponse(type=QUESTION|UNKNOWN)
  │     → session save → return ChatResponse
  │
  └─ LlmResponse.ToolCall → NlpChatResponse(type=TOOL_CALL, toolCall=ToolCallSpec)
        ↓
        ChatPipeline: IToolExecutor.execute(spec, userId) → ToolResult
        ↓
        append MessageRole.TOOL_RESULT to messages
        ↓
        AiClientImpl.chatSession(messages)  [LLM generates natural language]
        ↓
        session save + reset → return ChatResponse(type=RESULT)
```

---

## New Types — `core` Module

### `core/nlp/tools/Tool.kt`

```kotlin
@JvmInline value class ToolName(val value: String)
@JvmInline value class ToolDescription(val value: String)
@JvmInline value class ToolArguments(val value: String)  // JSON string

interface Tool {
    val name: ToolName
    val description: ToolDescription
    val schema: ToolSchema
}
```

### `core/nlp/tools/ToolSchema.kt`

```kotlin
@Serializable
data class ToolSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
)
```

### `core/commands/ToolCallSpec.kt`

Replaces `CommandName` / `CommandParams` / `CommandResult` pattern.

```kotlin
data class ToolCallSpec(val name: ToolName, val arguments: ToolArguments)

@JvmInline value class ToolResult(val value: String)

interface IToolExecutor {
    suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult
}
```

### `core/nlp/LlmTypes.kt` (modified)

```kotlin
@JvmInline value class LlmRawResponse(val value: String)  // kept

sealed class LlmResponse {
    data class Text(val content: LlmRawResponse) : LlmResponse()
    data class ToolCall(val spec: ToolCallSpec) : LlmResponse()
}
```

### `core/nlp/LlmBackend.kt` (modified)

```kotlin
interface LlmBackend {
    suspend fun complete(
        system: SystemPrompt,
        messages: List<Message>,
        tools: List<Tool> = emptyList(),
    ): LlmResponse
}
```

### `core/models/ApiModels.kt` (modified)

```kotlin
// NlpChatResponse — remove command/params, add toolCall, strengthen type
data class NlpChatResponse(
    val type: ChatResponseType,       // String → ChatResponseType
    val text: String,
    val toolCall: ToolCallSpec? = null,
)

// MessageRole — add TOOL_RESULT
enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL_RESULT("tool"),
}
```

### `core/nlp/ChatResponseType.kt` (modified)

```kotlin
enum class ChatResponseType(val value: String) {
    QUESTION("question"),
    RESULT("result"),
    TOOL_CALL("tool_call"),   // new
    UNKNOWN("unknown"),
    ERROR("error"),
}
```

---

## NLP Module Changes

### `AiClientImpl` (modified)

```kotlin
class AiClientImpl(
    private val backend: LlmBackend,
    private val promptConfig: PromptConfig,
    private val tools: List<Tool> = emptyList(),  // injected at construction
) : AiClient {
    override suspend fun chatSession(
        messages: List<Message>,
        contextResults: List<ContextResult>,
    ): NlpChatResponse {
        return when (val response = backend.complete(promptConfig.chatbotSystemPrompt, messages, tools)) {
            is LlmResponse.Text -> {
                val dto = json.decodeFromString<ChatSessionDto>(response.content.value)
                NlpChatResponse(type = ChatResponseType.valueOf(dto.type.uppercase()), text = dto.text)
            }
            is LlmResponse.ToolCall -> NlpChatResponse(
                type = ChatResponseType.TOOL_CALL,
                text = "",
                toolCall = response.spec,
            )
        }
    }
}
```

### `PromptToolInjector.kt` (new utility, `nlp` module)

Used by Ollama/OpenRouter backends to inject tool schemas into system prompt.

```kotlin
fun SystemPrompt.withTools(tools: List<Tool>): SystemPrompt {
    if (tools.isEmpty()) return this
    val toolsJson = Json.encodeToString(tools.map { ToolSchemaDto(it) })
    return SystemPrompt(
        "$value\n\n사용 가능한 도구 목록 (JSON Schema):\n$toolsJson\n\n" +
        "도구를 호출해야 할 경우 반드시 아래 형식으로만 응답하세요:\n" +
        "{\"tool_call\":{\"name\":\"도구이름\",\"arguments\":{...}}}"
    )
}
```

### Backend Changes

**`AnthropicBackend`** — when `tools` non-empty, use SDK native `tools` parameter:
- Map `Tool` → `ToolDefinition` SDK type
- If response contains `tool_use` block → `LlmResponse.ToolCall`
- Otherwise → `LlmResponse.Text`
- Handle `MessageRole.TOOL_RESULT` as `tool_result` content block

**`OllamaBackend` / `OpenRouterBackend`** — when `tools` non-empty, call `system.withTools(tools)`:
- Parse response: if JSON contains `tool_call` key → `LlmResponse.ToolCall`
- Otherwise → `LlmResponse.Text`
- Handle `MessageRole.TOOL_RESULT` as `user` message with `[Tool: name]\nresult` prefix

### New DTOs (`LlmResponseDtos.kt`)

```kotlin
// For prompt-injection backends
@Serializable
data class PromptInjectionResponseDto(
    @SerialName("tool_call") val toolCall: PromptInjectionToolCallDto? = null,
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class PromptInjectionToolCallDto(
    val name: String,
    val arguments: JsonObject,
)
```

---

## Pipeline Changes

### `ChatPipeline` (modified)

```kotlin
class ChatPipeline(
    private val sessions: SessionManager,
    private val aiClient: AiClient,
    private val toolExecutor: IToolExecutor,  // replaces ICommandExecutor
) : IChatPipeline {
    override suspend fun process(req: ChatRequest): ChatResponse {
        val sessionKey = SessionKey(req.platform, req.conversationId)
        val messages = sessions.getMessages(sessionKey).toMutableList()
        messages.add(Message.buildUserMessage(req.text))

        val nlpResponse = aiClient.chatSession(messages)

        return when (nlpResponse.type) {
            ChatResponseType.TOOL_CALL -> {
                val result = toolExecutor.execute(nlpResponse.toolCall!!, UserId(req.userId))
                sessions.addMessage(sessionKey, MessageRole.USER, req.text)
                messages.add(Message(MessageRole.TOOL_RESULT, result.value))
                val finalResponse = aiClient.chatSession(messages.toList())
                sessions.addMessage(sessionKey, MessageRole.ASSISTANT, finalResponse.text)
                sessions.resetSession(sessionKey)
                ChatResponse(type = ChatResponseType.RESULT.value, text = finalResponse.text, sessionReset = true)
            }
            else -> {
                sessions.addMessage(sessionKey, MessageRole.USER, req.text)
                sessions.addMessage(sessionKey, MessageRole.ASSISTANT, nlpResponse.text)
                ChatResponse(type = nlpResponse.type.value, text = nlpResponse.text)
            }
        }
    }
}
```

### `NoOpToolExecutor` (new, replaces `NoOpCommandExecutor`)

```kotlin
class NoOpToolExecutor : IToolExecutor {
    override suspend fun execute(spec: ToolCallSpec, userId: UserId): ToolResult =
        ToolResult("Tool '${spec.name.value}' not yet implemented.")
}
```

---

## Application Wiring (`Application.kt`)

```kotlin
val aiClient = AiClientFactory.create(NliPromptConfig(), tools = emptyList())
val pipeline = ChatPipeline(SessionManager(), aiClient, NoOpToolExecutor())
```

Tool list stays empty until `domain` module implements actual tools.

---

## Files to Modify

| File | Change |
|---|---|
| `core/nlp/LlmTypes.kt` | Add `LlmResponse` sealed class |
| `core/nlp/LlmBackend.kt` | Add `tools` param, change return type |
| `core/models/ApiModels.kt` | `NlpChatResponse`, `MessageRole` changes |
| `core/nlp/ChatResponseType.kt` | Add `TOOL_CALL` |
| `core/commands/ICommandExecutor.kt` | **Delete** — replaced by `IToolExecutor` |
| `nlp/pipeline/NoOpCommandExecutor.kt` | **Delete** — replaced by `NoOpToolExecutor` |
| `nlp/models/AiClientImpl.kt` | Handle `LlmResponse` sealed class, accept `tools` |
| `nlp/models/LlmResponseDtos.kt` | Add `PromptInjectionResponseDto`, `PromptInjectionToolCallDto` |
| `nlp/backend/anthropic/AnthropicBackend.kt` | Native tool calling |
| `nlp/backend/ollama/OllamaBackend.kt` | Prompt injection |
| `nlp/backend/openrouter/OpenRouterBackend.kt` | Prompt injection |
| `nlp/pipeline/ChatPipeline.kt` | Tool call loop |
| `app/Application.kt` | Wire `NoOpToolExecutor` |

## Files to Create

| File | Purpose |
|---|---|
| `core/nlp/tools/Tool.kt` | `Tool` interface + value classes |
| `core/nlp/tools/ToolSchema.kt` | `ToolSchema`, `PropertySchema` |
| `core/commands/ToolCallSpec.kt` | `ToolCallSpec`, `ToolResult`, `IToolExecutor` |
| `nlp/PromptToolInjector.kt` | `SystemPrompt.withTools()` extension |
| `nlp/pipeline/NoOpToolExecutor.kt` | Stub executor |

---

## Verification

1. `./gradlew build` — compile-time check all interface changes cascade correctly
2. `USE_DUMMY_PIPELINE=true ./gradlew :app:run` — server starts without LLM
3. `AI_PROVIDER=ollama` with empty tool list → behavior identical to current
4. Unit test: `AiClientImpl` with mock backend returning `LlmResponse.ToolCall` → pipeline executes executor → second LLM call made
5. Manual test with Anthropic backend: send a request that should trigger a (stubbed) tool call and verify two LLM calls occur
