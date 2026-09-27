package com.homeassistant.codex.transport

import com.homeassistant.codex.transport.process.StdioProcessTransport
import java.nio.file.Path

internal object CodexAppServerTransportFactory {
    fun create(command: List<String>, workDir: Path): CodexAppServerTransport =
        DefaultCodexAppServerTransport(
            StdioProcessTransport(command, workDir),
        )
}
