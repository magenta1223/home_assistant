package com.homeassistant.infrastructure.runtime

/**
 * The state of a [Runtime]. It only moves forward: `NEW → STARTING → RUNNING → STOPPING → TERMINATED`, and any
 * of `STARTING`, `RUNNING` or `STOPPING` may end in `FAILED`.
 */
enum class RuntimeState {
    /** Created and not yet started. */
    NEW,

    /** Opening its resources. */
    STARTING,

    /** Ready to serve. */
    RUNNING,

    /** Releasing its resources. */
    STOPPING,

    /** Stopped normally; the instance is not used again. */
    TERMINATED,

    /** Failed to start or stop, or died while running; the instance is not used again. */
    FAILED,
}
