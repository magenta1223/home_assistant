package com.homeassistant.llm.codex.conversation

import com.homeassistant.llm.codex.completion.CodexExecutableFactory
import com.homeassistant.llm.ChatClient
import java.nio.file.Path
import java.time.Duration

internal object CodexChatClientFactory {
    fun create(
        timeout: Duration,
        executable: String = CodexExecutableFactory.executable(),
        temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        model: String = CodexChatConfig.DEFAULT_MODEL,
        reasoningEffort: String = CodexChatConfig.DEFAULT_REASONING_EFFORT,
    ): ChatClient? {
        val config = CodexChatConfig.local(
            timeout = timeout,
            executable = executable,
            temporaryDirectory = temporaryDirectory,
            model = model,
            reasoningEffort = reasoningEffort,
        ) ?: return null
        return installed(CodexChatClient(config))
    }

    /** Returns [server] when its Codex CLI is installed, without starting it; otherwise null. */
    internal fun installed(server: CodexChatClient): CodexChatClient? =
        server.takeIf { runCatching(it::isInstalled).getOrDefault(false) }
}
