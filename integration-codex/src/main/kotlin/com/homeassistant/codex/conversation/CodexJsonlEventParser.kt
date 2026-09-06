package com.homeassistant.codex.conversation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
internal class CodexJsonlEventParser {
    fun parse(
        line: String,
        state: CodexEventState,
        onThreadStarted: (String) -> Unit,
    ) {
        if (state.failure.get() != null) return
        val event = runCatching {
            CODEX_JSON.parseToJsonElement(line).jsonObject
        }.getOrElse {
            state.failure.compareAndSet(null, "INVALID_JSONL")
            return
        }
        when (event.string("type")) {
            "thread.started" -> parseThreadStarted(event, state, onThreadStarted)
            "item.completed" -> parseCompletedItem(event, state)
            "turn.completed" -> state.turnCompleted.set(true)
            "turn.failed", "error" -> state.failure.compareAndSet(null, "TURN_FAILED")
        }
    }

    private fun parseThreadStarted(
        event: JsonObject,
        state: CodexEventState,
        callback: (String) -> Unit,
    ) {
        val threadId = event.string("thread_id")
        if (threadId == null || !THREAD_ID_PATTERN.matches(threadId)) {
            state.failure.compareAndSet(null, "INVALID_THREAD_ID")
        } else if (!state.threadStarted.compareAndSet(false, true)) {
            state.failure.compareAndSet(null, "DUPLICATE_THREAD_STARTED")
        } else {
            runCatching { callback(threadId) }
                .onFailure { state.failure.compareAndSet(null, "THREAD_PERSIST_FAILED") }
        }
    }

    private fun parseCompletedItem(event: JsonObject, state: CodexEventState) {
        val item = event["item"] as? JsonObject ?: return
        if (item.string("type") == "agent_message") {
            item.string("text")?.let(state.answer::set)
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content
}

internal val CODEX_THREAD_ID_PATTERN =
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

private val THREAD_ID_PATTERN = CODEX_THREAD_ID_PATTERN
