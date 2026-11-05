package com.homeassistant.infrastructure.runtime

import kotlinx.coroutines.flow.StateFlow

interface Runtime<I> {

    val state: StateFlow<RuntimeState>

    val failure: StateFlow<Throwable?>

    suspend fun create(input: I)

    suspend fun start()

    suspend fun stop()

    suspend fun destroy()
}