package com.homeassistant.adapter.inbound.slack

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable
private data class LegacySlackMemberConfig(
    val teamId: String,
    val slackUserId: String,
    val userId: String,
)

data class LegacySlackMemberMapping(
    val teamId: String,
    val slackUserId: String,
    val userId: UserId,
)

/** Reads the former fixed mapping only to preserve existing memory ownership during migration. */
internal object LegacySlackMemberMappings {
    fun fromJson(configuredTeamId: String, json: String): List<LegacySlackMemberMapping> {
        val records = JsonSerializer.json.decodeFromString<List<LegacySlackMemberConfig>>(json)
        require(records.isNotEmpty()) { "SLACK_MEMBER_SCOPES_JSON must not be empty" }

        val actors = mutableSetOf<Pair<String, String>>()
        return records.map { record ->
            require(record.teamId == configuredTeamId) {
                "Slack member mapping team does not match SLACK_TEAM_ID"
            }
            require(actors.add(record.teamId to record.slackUserId)) {
                "Duplicate Slack member mapping"
            }
            LegacySlackMemberMapping(
                teamId = record.teamId,
                slackUserId = record.slackUserId,
                userId = UserId(record.userId),
            )
        }
    }
}
