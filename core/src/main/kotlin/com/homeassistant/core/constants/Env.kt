package com.homeassistant.core.constants

import io.github.cdimascio.dotenv.dotenv

object Env {
    private val dotenv = dotenv { ignoreIfMissing = true }

    operator fun get(key: String): String? = dotenv[key]
}
