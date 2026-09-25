package com.homeassistant.codex.rpcclient

import java.nio.file.Path

internal object CodexRpcClientFactory {
    fun create(command: List<String>, workDir: Path): CodexRpcClient =
        StdioCodexRpcClient(command, workDir)
}
