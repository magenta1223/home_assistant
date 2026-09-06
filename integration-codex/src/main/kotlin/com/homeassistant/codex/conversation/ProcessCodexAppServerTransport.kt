package com.homeassistant.codex.conversation

import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ProcessCodexAppServerTransport(
    private val command: List<String>,
    private val workDir: Path,
) : AppServerTransport {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "codex-app-server-io").apply { isDaemon = true }
    }
    private val running = AtomicReference<RunningProcess>()
    private val sendLock = Any()
    private val closed = AtomicBoolean(false)

    override val isAlive: Boolean
        get() = running.get()?.process?.isAlive == true

    override fun start(onMessage: (String) -> Unit, onClosed: () -> Unit): Boolean = synchronized(this) {
        if (closed.get()) return false
        if (isAlive) return true
        val process = runCatching {
            ProcessBuilder(command)
                .directory(workDir.toFile())
                .start()
        }.getOrElse { return false }
        val active = RunningProcess(
            process = process,
            writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8),
            onClosed = onClosed,
        )
        running.set(active)
        readers.submit {
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        runCatching { onMessage(line) }
                            .onFailure {
                                log.warn("Codex app-server message handler failed category={}", it.javaClass.simpleName)
                            }
                    }
                }
            } finally {
                notifyClosed(active)
            }
        }
        readers.submit {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { log.debug("Codex app-server stderr category=PROCESS_OUTPUT") }
            }
        }
        true
    }

    override fun send(message: String) {
        val active = running.get()?.takeIf { it.process.isAlive }
            ?: error("Codex app-server is not running")
        synchronized(sendLock) {
            active.writer.write(message)
            active.writer.newLine()
            active.writer.flush()
        }
    }

    override fun stop() {
        running.getAndSet(null)?.let(::destroy)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        readers.shutdownNow()
    }

    private fun notifyClosed(active: RunningProcess) {
        if (running.compareAndSet(active, null) && active.notified.compareAndSet(false, true)) {
            active.onClosed()
        }
    }

    private fun destroy(active: RunningProcess) {
        runCatching { active.writer.close() }
        active.process.descendants().forEach(ProcessHandle::destroyForcibly)
        active.process.destroyForcibly()
    }

    private data class RunningProcess(
        val process: Process,
        val writer: BufferedWriter,
        val onClosed: () -> Unit,
        val notified: AtomicBoolean = AtomicBoolean(false),
    )
}
