package com.homeassistant.llm.codex.transport.process

/** Owns one OS process and transports newline-delimited messages through its standard streams. */
internal interface ProcessTransport : AutoCloseable {
    /** Starts the process. A `true` result means [send] may be called immediately. */
    fun start(listener: Listener): Boolean

    /** Writes one complete message to the running process. */
    fun send(message: String)

    /** Stops the current process without reporting an unexpected exit. A later [start] may start another process. */
    fun stop()

    /** Permanently closes this transport and its current process. */
    override fun close()

    interface Listener {
        fun onMessage(message: String)

        fun onProcessExited()
    }
}
