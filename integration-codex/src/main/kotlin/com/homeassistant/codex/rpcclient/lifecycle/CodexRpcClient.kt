package com.homeassistant.codex.rpcclient.lifecycle

/** An opaque identity for one execution, not a snapshot of its health. */
internal class RpcSession internal constructor()

internal interface CodexRpcClient : AutoCloseable {
    /** Returns null when closed or unable to start. Concurrent lifecycle calls wait their turn. */
    fun start(onMessage: (RpcSession, String) -> Unit, onClosed: (RpcSession) -> Unit): RpcSession?
    fun send(session: RpcSession, message: String)
    fun stop()
}
