package com.homeassistant.adapter.outbound.runtime.distribution

internal object WindowsX64Platform {
    fun isSupported(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")
}
