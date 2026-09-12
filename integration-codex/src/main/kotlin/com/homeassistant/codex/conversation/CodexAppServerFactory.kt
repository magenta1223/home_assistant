package com.homeassistant.codex.conversation

import com.homeassistant.codex.completion.CodexExecutableFactory
import java.nio.file.Path
import java.time.Duration

object CodexAppServerFactory {
    fun create(
        timeout: Duration,
        executable: String = CodexExecutableFactory.get(),
        temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        model: String = DEFAULT_MODEL,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    ): CodexAppServer? {
        val config = CodexConversationConfig.local(
            timeout = timeout,
            executable = executable,
            temporaryDirectory = temporaryDirectory,
            model = model,
            reasoningEffort = reasoningEffort,
        ) ?: return null
        val server = DefaultCodexAppServer(config)
        return server.takeIf { runCatching(it::prepare).getOrDefault(false) }
            ?: run {
                server.close()
                null
            }
    }

    internal fun create(
        config: CodexConversationConfig,
        transport: AppServerTransport,
        availabilityProbe: () -> Boolean,
    ): CodexAppServer? {
        val server = DefaultCodexAppServer(config, transport, availabilityProbe)
        return server.takeIf { runCatching(it::prepare).getOrDefault(false) }
            ?: run {
                server.close()
                null
            }
    }

    private const val DEFAULT_MODEL = "gpt-5.6-luna"
    private const val DEFAULT_REASONING_EFFORT = "medium"
}
