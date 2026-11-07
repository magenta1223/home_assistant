package com.homeassistant.llm.codex.conversation

import com.homeassistant.llm.codex.completion.CodexExecutableFactory
import com.homeassistant.llm.ChatClient
import java.nio.file.Path
import java.time.Duration

internal object CodexAppServerFactory {
    fun create(
        timeout: Duration,
        executable: String = CodexExecutableFactory.get(),
        temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        model: String = CodexConversationConfig.DEFAULT_MODEL,
        reasoningEffort: String = CodexConversationConfig.DEFAULT_REASONING_EFFORT,
    ): ChatClient? {
        val config = CodexConversationConfig.local(
            timeout = timeout,
            executable = executable,
            temporaryDirectory = temporaryDirectory,
            model = model,
            reasoningEffort = reasoningEffort,
        ) ?: return null
        return installed(DefaultCodexAppServer(config))
    }

    /** Returns [server] when its Codex CLI is installed, without starting it; otherwise null. */
    internal fun installed(server: DefaultCodexAppServer): DefaultCodexAppServer? =
        server.takeIf { runCatching(it::isInstalled).getOrDefault(false) }
}
