package com.homeassistant.codex.conversation

internal interface AppServerTransport : AutoCloseable {
    val isAlive: Boolean
    fun start(onMessage: (String) -> Unit, onClosed: () -> Unit): Boolean
    fun send(message: String)
    fun stop()
}
