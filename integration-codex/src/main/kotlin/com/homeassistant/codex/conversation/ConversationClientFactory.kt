package com.homeassistant.codex.conversation

import com.homeassistant.codex.completion.CodexExecutableFactory
import java.nio.file.Path
import java.time.Duration

object ConversationClientFactory {
    fun create(
        timeout: Duration,
        executable: String = CodexExecutableFactory.get(),
        temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        model: String = DEFAULT_MODEL,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    ): ConversationClient? = CodexConversationConfig.local(
        timeout = timeout,
        executable = executable,
        temporaryDirectory = temporaryDirectory,
        model = model,
        reasoningEffort = reasoningEffort,
    )?.let(::CodexAppServerConversationClient)

    private const val DEFAULT_MODEL = "gpt-5.6-luna"
    private const val DEFAULT_REASONING_EFFORT = "medium"
}
