package com.homeassistant.llm.codex.conversation

import com.homeassistant.llm.codex.completion.CodexExecutableFactory
import com.homeassistant.llm.conversation.ConversationClient
import java.nio.file.Path
import java.time.Duration

internal object CodexAppServerFactory {
    fun create(
        timeout: Duration,
        executable: String = CodexExecutableFactory.get(),
        temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        model: String = CodexConversationConfig.DEFAULT_MODEL,
        reasoningEffort: String = CodexConversationConfig.DEFAULT_REASONING_EFFORT,
    ): ConversationClient? {
        val config = CodexConversationConfig.local(
            timeout = timeout,
            executable = executable,
            temporaryDirectory = temporaryDirectory,
            model = model,
            reasoningEffort = reasoningEffort,
        ) ?: return null
        return prepare(DefaultCodexAppServer(config))
    }

    /** Returns [server] once it is available and initialized; otherwise closes it and returns null. */
    internal fun prepare(server: DefaultCodexAppServer): ConversationClient? =
        server.takeIf { runCatching(it::prepare).getOrDefault(false) }
            ?: run {
                server.close()
                null
            }
}
