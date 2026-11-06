package com.homeassistant.infrastructure.runtime

/**
 * A component that lives as long as the application, such as a managed process or a scheduled worker. It starts
 * once and stops once; running it again takes a new instance.
 */
interface Runtime {
    /** The current state. */
    val state: RuntimeState

    /** Blocks until [RuntimeState.RUNNING]. On failure it leaves [RuntimeState.FAILED] and throws the cause. Only valid in [RuntimeState.NEW]. */
    fun start()

    /** Blocks until the resources are released. Calling it again, or after a failure, does nothing. */
    fun stop()
}
