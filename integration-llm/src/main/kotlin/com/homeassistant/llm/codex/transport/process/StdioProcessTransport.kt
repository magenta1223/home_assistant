package com.homeassistant.llm.codex.transport.process

import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class StdioProcessTransport(
    private val command: List<String>,
    private val workDir: Path,
) : ProcessTransport {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lifecycleLock = Any()
    private val sendLock = Any()
    private val ioTasks: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "codex-app-server-io").apply { isDaemon = true }
    }

    @Volatile
    private var state: State = Stopped

    override fun start(listener: ProcessTransport.Listener): Boolean {

        var terminating: Terminating? = null

        val result = synchronized(lifecycleLock) {
            when (val current = state) {
                Closed -> false // starting after close is unavailable
                Stopped -> startProcess(listener) // only true if process started correctly
                is Running -> {
                    if (current.process.isAlive) {
                        // already started
                        true
                    } else {
                        // process is dead unexpectedly
                        terminating = Terminating(current)
                        state = terminating
                        false
                    }
                }
                is Terminating -> false // starting when terminating is unavailable
            }
        }
        terminating?.let { finishUnexpectedTermination(it) }
        return result
    }


    override fun send(message: String) {
        val running = synchronized(lifecycleLock) {
            when (val current = state) {
                Closed -> throw ProcessTransportException("Process is not running") // sending after close is unavailable
                Stopped -> throw ProcessTransportException("Process is not running") // no process exists
                is Running -> current // only a running process can receive a message
                is Terminating -> throw ProcessTransportException("Process is not running") // the dead process is being cleaned up
            }
        }

        val failure = runCatching {
            synchronized(sendLock) {
                // The lifecycle may change after selecting the process and before acquiring the send lock.
                val writable = synchronized(lifecycleLock) {
                    state === running && running.process.isAlive
                }
                check(writable) { "Process is not running" }
                running.writer.write(message)
                running.writer.newLine()
                running.writer.flush()
            }
        }.exceptionOrNull()
        if (failure != null) {
            terminateUnexpectedly(running)
            throw ProcessTransportException("Failed to write to process", failure)
        }
    }

    override fun stop() {
        val running = synchronized(lifecycleLock) {
            when (val current = state) {
                Closed -> null // close already completed the terminal shutdown
                Stopped -> null // no process exists
                is Running -> {
                    // Mark the shutdown as expected before destroying the process, so no exit callback is emitted.
                    state = Stopped
                    current
                }
                is Terminating -> null // unexpected-exit cleanup already owns process destruction
            }
        }
        running?.let(::destroy)
    }

    override fun close() {
        val running = synchronized(lifecycleLock) {
            when (val current = state) {
                Closed -> return // close is idempotent
                Stopped -> null // only the I/O executor remains to be shut down
                is Running -> current // close owns destruction of the active process
                is Terminating -> current.running // close takes over destruction from unexpected-exit cleanup
            }.also { state = Closed }
        }
        running?.let(::destroy)
        ioTasks.shutdownNow()
    }

    private fun startProcess(listener: ProcessTransport.Listener): Boolean {
        val process = runCatching {
            ProcessBuilder(command)
                .directory(workDir.toFile())
                .start()
        }.getOrElse { return false }
        val running = Running(
            process = process,
            writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8),
            listener = listener,
        )
        state = running
        return try {
            ioTasks.submit { readOutput(running) }
            ioTasks.submit { drainErrors(running) }
            ioTasks.submit { awaitExit(running) }
            if (!process.isAlive) {
                state = Stopped
                destroy(running)
                false
            } else {
                true
            }
        } catch (error: Exception) {
            state = Stopped
            destroy(running)
            false
        }
    }

    private fun readOutput(running: Running) {
        try {
            running.process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { message ->
                    if (state !== running) return@forEach
                    runCatching { running.listener.onMessage(message) }
                        .onFailure {
                            log.warn("Process message listener failed category={}", it.javaClass.simpleName)
                        }
                }
            }
        } catch (error: Exception) {
            if (state === running) {
                log.debug("Process stdout reader stopped category={}", error.javaClass.simpleName)
            }
        } finally {
            running.stdoutCompleted.complete(Unit)
            terminateUnexpectedly(running)
        }
    }

    private fun drainErrors(running: Running) {
        runCatching {
            running.process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { _ -> log.debug("Process stderr category=PROCESS_OUTPUT") }
            }
        }
    }

    private fun awaitExit(running: Running) {
        runCatching { running.process.waitFor() }
        runCatching { running.stdoutCompleted.get(STDOUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        terminateUnexpectedly(running)
    }

    private fun terminateUnexpectedly(running: Running) {
        val terminating = synchronized(lifecycleLock) {
            if (state !== running) return
            Terminating(running).also { state = it }
        }
        finishUnexpectedTermination(terminating)
    }

    private fun finishUnexpectedTermination(terminating: Terminating) {
        destroy(terminating.running)
        runCatching { terminating.running.listener.onProcessExited() }
            .onFailure {
                log.warn("Process exit listener failed category={}", it.javaClass.simpleName)
            }
        synchronized(lifecycleLock) {
            if (state === terminating) state = Stopped
        }
    }

    private fun destroy(running: Running) {
        running.process.descendants().toList().forEach { runCatching { it.destroyForcibly() } }
        runCatching { running.process.destroyForcibly() }
        runCatching { running.process.outputStream.close() }
        runCatching { running.process.inputStream.close() }
        runCatching { running.process.errorStream.close() }
        runCatching { running.writer.close() }
    }

    private sealed interface State

    private data object Stopped : State

    private data class Running(
        val process: Process,
        val writer: BufferedWriter,
        val listener: ProcessTransport.Listener,
        val stdoutCompleted: CompletableFuture<Unit> = CompletableFuture(),
    ) : State

    private data class Terminating(val running: Running) : State

    private data object Closed : State

    private companion object {
        const val STDOUT_DRAIN_TIMEOUT_SECONDS = 1L
    }

    private class ProcessTransportException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)
}
