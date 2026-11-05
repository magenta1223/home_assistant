package com.homeassistant.infrastructure.runtime

enum class RuntimeState {
    UNINITIALIZED,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
    DESTROYED
}