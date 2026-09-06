package com.homeassistant.codex.subprocess

data class ProcessResult(
    val exitCode: Int,
    val stderr: String,
    val timedOut: Boolean = false,
)