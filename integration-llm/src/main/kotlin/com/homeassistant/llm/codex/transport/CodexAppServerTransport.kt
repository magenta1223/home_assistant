package com.homeassistant.llm.codex.transport

import com.homeassistant.llm.codex.transport.protocol.AppServerProtocol
import com.homeassistant.llm.codex.transport.protocol.ThreadResult
import com.homeassistant.llm.codex.transport.protocol.TurnStartResult
import java.util.concurrent.CompletableFuture

/**
 * Transports typed requests and notifications to one Codex app-server process.
 *
 * One transport owns one app-server process. The process may own multiple Codex threads, identified by the
 * `threadId` carried by the thread- and turn-scoped protocol messages below.
 */
internal interface CodexAppServerTransport : AutoCloseable {
    /** Starts the app-server process and registers the listener for that process. */
    fun start(listener: Listener): Boolean

    /** Performs the app-server initialization handshake. */
    fun initialize(request: AppServerProtocol.Initialize): CompletableFuture<Unit>

    /** Creates a new Codex thread in the running app-server process. */
    fun startThread(request: AppServerProtocol.ThreadStart): CompletableFuture<ThreadResult>

    /** Loads the Codex thread identified by [AppServerProtocol.ThreadResume.threadId]. */
    fun resumeThread(request: AppServerProtocol.ThreadResume): CompletableFuture<ThreadResult>

    /** Starts a turn on the Codex thread identified by [AppServerProtocol.TurnStart.threadId]. */
    fun startTurn(request: AppServerProtocol.TurnStart): CompletableFuture<TurnStartResult>

    /** Interrupts a turn on the Codex thread identified by [AppServerProtocol.TurnInterrupt.threadId]. */
    fun interruptTurn(request: AppServerProtocol.TurnInterrupt): CompletableFuture<Unit>

    /** Stops receiving events for the Codex thread identified by [AppServerProtocol.ThreadUnsubscribe.threadId]. */
    fun unsubscribeThread(request: AppServerProtocol.ThreadUnsubscribe): CompletableFuture<Unit>

    /** Stops the current app-server process. A later [start] may launch another process. */
    fun stop()

    /** Permanently closes this transport and its current app-server process. */
    override fun close()

    /** Receives asynchronous events emitted by the current app-server process. */
    interface Listener {
        fun onItemCompleted(event: AppServerProtocol.ItemCompleted)

        fun onTurnCompleted(event: AppServerProtocol.TurnCompleted)

        fun onThreadClosed(event: AppServerProtocol.ThreadClosed)

        /** Called when an app-server message cannot be decoded according to the known protocol. */
        fun onProtocolError()

        /** Called when the app-server process exits unexpectedly. */
        fun onAppServerExited()
    }
}
