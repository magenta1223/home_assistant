package com.homeassistant.infrastructure.runtime

import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the state transitions of a [Runtime]; an implementation only opens its resources in [onStart], releases
 * them in [onStop], and reports a resource that died on its own with [fail].
 *
 * [start] and [stop] are serialized, so a [stop] during [RuntimeState.STARTING] waits until [start] returns.
 * [fail] never waits, so a resource may report its death while [onStart] is still running.
 */
abstract class AbstractRuntime : Runtime {
    private val lifecycleLock = Any()
    private val current = AtomicReference(RuntimeState.NEW)

    @Volatile
    private var failure: Throwable? = null

    final override val state: RuntimeState
        get() = current.get()

    /** Opens the resources and returns once they are ready. A throw leaves [RuntimeState.FAILED]; [onStop] is not called, so release what was opened before throwing. */
    protected abstract fun onStart()

    /** Releases the resources. A throw leaves [RuntimeState.FAILED]. */
    protected abstract fun onStop()

    final override fun start() {
        synchronized(lifecycleLock) {
            check(current.compareAndSet(RuntimeState.NEW, RuntimeState.STARTING)) {
                "${javaClass.simpleName} cannot start in state ${current.get()}"
            }
            runCatching(::onStart).fold(
                onSuccess = {
                    if (!current.compareAndSet(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                        throw IllegalStateException("${javaClass.simpleName} failed while starting", failure)
                    }
                },
                onFailure = { cause -> failWith(cause) },
            )
        }
    }

    final override fun stop() {
        synchronized(lifecycleLock) {
            when {
                current.compareAndSet(RuntimeState.NEW, RuntimeState.TERMINATED) -> Unit
                current.compareAndSet(RuntimeState.RUNNING, RuntimeState.STOPPING) -> runCatching(::onStop).fold(
                    onSuccess = { current.set(RuntimeState.TERMINATED) },
                    onFailure = { cause -> failWith(cause) },
                )
                else -> Unit
            }
        }
    }

    /** Marks a resource that died on its own while [RuntimeState.STARTING] or [RuntimeState.RUNNING] as [RuntimeState.FAILED]. */
    protected fun fail(cause: Throwable) {
        if (current.compareAndSet(RuntimeState.RUNNING, RuntimeState.FAILED) ||
            current.compareAndSet(RuntimeState.STARTING, RuntimeState.FAILED)
        ) {
            failure = cause
        }
    }

    private fun failWith(cause: Throwable): Nothing {
        failure = cause
        current.set(RuntimeState.FAILED)
        throw cause
    }
}
