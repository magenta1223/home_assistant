package com.homeassistant.codex.rpcclient.lifecycle

import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class StdioCodexRpcClient(
    private val command: List<String>,
    private val workDir: Path,
) : AbstractCodexRpcClient<StdioCodexRpcClient.Resource>() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun openResource(): Resource {
        val process = ProcessBuilder(command).directory(workDir.toFile()).start()
        return try {
            Resource(process, process.outputStream.bufferedWriter(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            releaseProcess(process, null)
            throw error
        }
    }

    override fun isResourceAlive(resource: Resource): Boolean = resource.process.isAlive

    override fun writeMessage(resource: Resource, message: String) {
        resource.writer.write(message)
        resource.writer.newLine()
        resource.writer.flush()
    }

    override fun readMessages(resource: Resource, emit: (String) -> Unit) {
        resource.process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach(emit)
        }
    }

    override fun readDiagnostics(resource: Resource) {
        resource.process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { log.debug("Codex app-server stderr category=PROCESS_OUTPUT") }
        }
    }

    override fun releaseResource(resource: Resource) {
        releaseProcess(resource.process, resource.writer)
    }

    private fun releaseProcess(process: Process, writer: BufferedWriter?) {
        // Terminate before closing the writer: close may wait behind a blocked flush.
        val descendants = runCatching { process.descendants().use { it.toList() } }.getOrDefault(emptyList())
        descendants.forEach { child -> cleanup { child.destroyForcibly() } }
        cleanup { process.destroyForcibly() }
        cleanup { check(process.waitFor(5, TimeUnit.SECONDS)) { "Process termination timed out" } }
        cleanup { writer?.close() }
        cleanup { process.inputStream.close() }
        cleanup { process.errorStream.close() }
        cleanup { process.outputStream.close() }
    }

    private fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log.warn("Codex process cleanup failed category={}", error.javaClass.simpleName)
        }
    }

    internal class Resource(val process: Process, val writer: BufferedWriter)
}
