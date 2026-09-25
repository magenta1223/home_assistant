package com.homeassistant.codex.rpcclient

internal interface CodexRpcClient : AutoCloseable {
    val isAlive: Boolean
    fun start(onMessage: (String) -> Unit, onClosed: () -> Unit): Boolean
    fun send(message: String)
    fun stop()
}
