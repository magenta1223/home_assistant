package com.homeassistant.adapter.inbound.slack

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable
data class SlackMemberScopeConfig(
    val teamId: String,
    val slackUserId: String,
    val userId: String,
)

/** Maps Slack identities to application users and exposes access policy. */
interface SlackIdentityDirectory {
    /** Provides the access policy derived from configured Slack members. */
    val accessPolicy: HouseholdAccessPolicy

    /** Immutable application users configured for this Slack workspace. */
    val userIds: Set<UserId>

    /** Resolves a configured Slack actor to its application user. */
    fun resolve(teamId: String?, slackUserId: String?): UserId?
}

private class ConfiguredSlackIdentityDirectory(
    private val users: Map<SlackActor, UserId>,
) : SlackIdentityDirectory {
    override val userIds: Set<UserId> = users.values.toCollection(linkedSetOf())
    override val accessPolicy: HouseholdAccessPolicy =
        HouseholdAccessPolicies.fixed(userIds)

    override fun resolve(teamId: String?, slackUserId: String?): UserId? {
        if (teamId.isNullOrBlank() || slackUserId.isNullOrBlank()) return null
        return users[SlackActor(teamId, slackUserId)]
    }
}

object SlackIdentityDirectoryFactory {
    fun fromJson(
        configuredTeamId: String,
        json: String,
    ): SlackIdentityDirectory {
        require(configuredTeamId.isNotBlank()) { "SLACK_TEAM_ID is required" }
        val records = JsonSerializer.json.decodeFromString<List<SlackMemberScopeConfig>>(json)
        require(records.isNotEmpty()) { "SLACK_MEMBER_SCOPES_JSON must not be empty" }

        val users = LinkedHashMap<SlackActor, UserId>()
        records.forEach { record ->
            require(record.teamId == configuredTeamId) {
                "Slack member mapping team does not match SLACK_TEAM_ID"
            }
            val previous = users.put(
                SlackActor(record.teamId, record.slackUserId),
                UserId(record.userId),
            )
            require(previous == null) { "Duplicate Slack member mapping" }
        }
        return ConfiguredSlackIdentityDirectory(users)
    }
}

private data class SlackActor(
    val teamId: String,
    val slackUserId: String,
)
