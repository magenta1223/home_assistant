package com.homeassistant.llm.codex.transport

import com.homeassistant.llm.codex.transport.process.StdioProcessTransport
import java.nio.file.Path

internal object CodexAppServerTransportFactory {
    fun create(command: List<String>, workDir: Path): CodexAppServerTransport =
        DefaultCodexAppServerTransport(
            StdioProcessTransport(command, workDir),
        )
}
