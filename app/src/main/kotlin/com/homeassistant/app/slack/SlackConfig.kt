package com.homeassistant.app.slack

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env

data class SlackConfig(
    val appToken: String,
    val botToken: String,
    val maxFileSizeBytes: Long,
) {
    companion object {
        fun fromEnv(): SlackConfig? {
            val appToken = Env[AppConfig.ENV_VAR_SLACK_APP_TOKEN]?.takeIf { it.isNotBlank() }
            val botToken = Env[AppConfig.ENV_VAR_SLACK_BOT_TOKEN]?.takeIf { it.isNotBlank() }
            if (appToken == null || botToken == null) return null

            val maxFileSizeBytes = Env[AppConfig.ENV_VAR_SLACK_MAX_FILE_SIZE_BYTES]
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: AppConfig.DEFAULT_SLACK_MAX_FILE_SIZE_BYTES

            return SlackConfig(
                appToken = appToken,
                botToken = botToken,
                maxFileSizeBytes = maxFileSizeBytes,
            )
        }
    }
}
