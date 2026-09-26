package com.homeassistant.codex.rpcclient.lifecycle

import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal abstract class AbstractCodexRpcClient<R : Any>(
    private val readers: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "codex-rpc-reader").apply { isDaemon = true }
    },
) : CodexRpcClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lifecycleLock = Any()
    private var state: State<R> = Stopped

    final override fun start(
        onMessage: (RpcSession, String) -> Unit,
        onClosed: (RpcSession) -> Unit,
    ): RpcSession? {
        refreshState()
        return synchronized(lifecycleLock) {
            when (val current = state) {
                Closed -> null
                Stopped -> startExecution(onMessage, onClosed)
                is Running -> current.id
            }
        }
    }

    final override fun send(session: RpcSession, message: String) {
        val current = synchronized(lifecycleLock) {
            val running = state as? Running<R>
            check(running != null && running.id === session) { "Codex RPC session is not running" }
            running
        }
        try {
            synchronized(current.sendLock) {
                check(isCurrentExecution(current)) { "Codex RPC session has stopped" }
                check(isResourceAlive(current.resource)) { "Codex RPC resource is not alive" }
                writeMessage(current.resource, message)
            }
        } catch (error: Exception) {
            onUnexpectedlyClosed(current)
            throw error
        }
    }

    final override fun stop() = synchronized(lifecycleLock) {
        val current = state
        if (current is Running) stopExecution(current)
    }

    final override fun close() = synchronized(lifecycleLock) {
        if (state === Closed) return
        val current = state
        if (current is Running) stopExecution(current)
        state = Closed
        readers.shutdown()
    }

    private fun isCurrentExecution(execution: Running<R>): Boolean =
        synchronized(lifecycleLock) { state === execution }

    private fun refreshState() {
        val ended = synchronized(lifecycleLock) {
            val current = state as? Running<R> ?: return
            if (isResourceAlive(current.resource)) return
            stopExecution(current)
            current
        }
        notifyClosed(ended)
    }

    // State transitions below run under lifecycleLock. Only notification runs outside it.
    private fun startExecution(
        onMessage: (RpcSession, String) -> Unit,
        onClosed: (RpcSession) -> Unit,
    ): RpcSession? {
        val resource = try {
            openResource()
        } catch (error: Exception) {
            logFailure("open", error)
            return null
        }
        val execution = Running(RpcSession(), resource, onMessage, onClosed)
        try {
            startReaders(execution)
        } catch (error: Exception) {
            logFailure("start-readers", error)
            release(resource)
            return null
        }
        state = execution
        return execution.id
    }

    private fun stopExecution(execution: Running<R>) {
        state = Stopped
        release(execution.resource)
    }

    private fun onUnexpectedlyClosed(execution: Running<R>) {
        synchronized(lifecycleLock) {
            if (state !== execution) return
            stopExecution(execution)
        }
        notifyClosed(execution)
    }

    private fun notifyClosed(execution: Running<R>) {
        safely("closed-handler") { execution.onClosed(execution.id) }
    }

    private fun release(resource: R) {
        safely("release") { releaseResource(resource) }
    }

    private fun startReaders(execution: Running<R>) {
        readers.submit { receiveMessages(execution) }
        readers.submit {
            try {
                readDiagnostics(execution.resource)
            } catch (error: Exception) {
                logFailure("diagnostics", error)
                onUnexpectedlyClosed(execution)
            }
        }
    }

    private fun receiveMessages(execution: Running<R>) {
        try {
            readMessages(execution.resource) { line ->
                if (isCurrentExecution(execution)) {
                    safely("message-handler") { execution.onMessage(execution.id, line) }
                }
            }
        } catch (error: Exception) {
            logFailure("read", error)
        } finally {
            onUnexpectedlyClosed(execution)
        }
    }

    private fun safely(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logFailure(operation, error)
        }
    }

    private fun logFailure(operation: String, error: Exception) {
        log.warn("Codex RPC operation={} failed category={}", operation, error.javaClass.simpleName)
    }

    /** Failure must clean up any resource that cannot be returned. */
    protected abstract fun openResource(): R
    /** Fast, non-throwing observation; may race with resource release. */
    protected abstract fun isResourceAlive(resource: R): Boolean
    protected abstract fun writeMessage(resource: R, message: String)
    /** Return on EOF; throw on failure. Both end the execution. */
    protected abstract fun readMessages(resource: R, emit: (String) -> Unit)
    protected abstract fun readDiagnostics(resource: R)
    /** Unblock I/O and attempt all cleanup; never wait for reader tasks or call back into the client. */
    protected abstract fun releaseResource(resource: R)

    private sealed interface State<out R>
    private data object Stopped : State<Nothing>
    private data object Closed : State<Nothing>
    private class Running<R>(
        val id: RpcSession,
        val resource: R,
        val onMessage: (RpcSession, String) -> Unit,
        val onClosed: (RpcSession) -> Unit,
        val sendLock: Any = Any(),
    ) : State<R>
}
