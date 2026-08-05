package com.homeassistant.adapter.inbound.slack

import com.homeassistant.adapter.shared.config.AppConfig
import com.homeassistant.adapter.shared.config.Env

data class SlackConfig(
    val appToken: String,
    val botToken: String,
    val teamId: String,
    val identityDirectory: SlackIdentityDirectory,
    val maxFileSizeBytes: Long,
) {
    companion object {
        fun fromEnv(): SlackConfig? {
            val appToken = Env[AppConfig.ENV_VAR_SLACK_APP_TOKEN]?.takeIf { it.isNotBlank() }
            val botToken = Env[AppConfig.ENV_VAR_SLACK_BOT_TOKEN]?.takeIf { it.isNotBlank() }
            val teamId = Env[AppConfig.ENV_VAR_SLACK_TEAM_ID]?.takeIf { it.isNotBlank() }
            val mappingsJson = Env[AppConfig.ENV_VAR_SLACK_MEMBER_SCOPES_JSON]
                ?.takeIf { it.isNotBlank() }
            if (appToken == null || botToken == null || teamId == null || mappingsJson == null) return null
            val identityDirectory = runCatching {
                SlackIdentityDirectoryFactory.fromJson(teamId, mappingsJson)
            }.getOrNull() ?: return null

            val maxFileSizeBytes = Env[AppConfig.ENV_VAR_SLACK_MAX_FILE_SIZE_BYTES]
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: AppConfig.DEFAULT_SLACK_MAX_FILE_SIZE_BYTES

            return SlackConfig(
                appToken = appToken,
                botToken = botToken,
                teamId = teamId,
                identityDirectory = identityDirectory,
                maxFileSizeBytes = maxFileSizeBytes,
            )
        }
    }
}
