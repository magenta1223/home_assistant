package com.homeassistant.codex.conversation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class CodexEventState(
    val answer: AtomicReference<String> = AtomicReference(),
    val failure: AtomicReference<String> = AtomicReference(),
    val turnCompleted: AtomicBoolean = AtomicBoolean(false),
    val threadStarted: AtomicBoolean = AtomicBoolean(false),
)
