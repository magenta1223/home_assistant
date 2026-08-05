package com.homeassistant.domain.slackconversation

import com.homeassistant.domain.identity.FamilyId
import com.homeassistant.domain.identity.HouseholdAccessScope
import com.homeassistant.domain.identity.UserId

data class SlackPrincipal(
    val teamId: String,
    val slackUserId: String,
    val scope: HouseholdAccessScope,
) {
    init {
        require(teamId.isNotBlank()) { "teamId is required" }
        require(slackUserId.isNotBlank()) { "slackUserId is required" }
    }

    constructor(
        teamId: String,
        slackUserId: String,
        userId: String,
        familyId: String,
    ) : this(
        teamId = teamId,
        slackUserId = slackUserId,
        scope = HouseholdAccessScope(UserId(userId), FamilyId(familyId)),
    )

    val userId: UserId get() = scope.userId
    val familyId: FamilyId get() = scope.familyId
}

data class SlackMessageKey(
    val channelId: String,
    val messageTs: String,
) {
    init {
        require(channelId.isNotBlank()) { "channelId is required" }
        require(messageTs.isNotBlank()) { "messageTs is required" }
    }
}

enum class SlackMessageReceiptStatus {
    PROCESSING,
    ANSWER_READY,
    COMPLETED,
    FAILED,
}

data class SlackCodexSession(
    val id: Int,
    val principal: SlackPrincipal,
    val codexThreadId: String,
    val createdAt: Long,
    val lastActiveAt: Long,
)

data class SlackMessageReceipt(
    val key: SlackMessageKey,
    val status: SlackMessageReceiptStatus,
    val sessionId: Int? = null,
    val answerText: String? = null,
    val responseTs: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

interface SlackCodexSessionStore {
    fun claimMessage(key: SlackMessageKey, now: Long): SlackMessageReceipt?
    fun receipt(key: SlackMessageKey): SlackMessageReceipt?
    fun attachSession(key: SlackMessageKey, sessionId: Int, now: Long)
    fun markAnswerReady(key: SlackMessageKey, answer: String, now: Long)
    fun markCompleted(key: SlackMessageKey, responseTs: String, now: Long)
    fun markFailed(key: SlackMessageKey, now: Long)
    fun createAndActivate(
        principal: SlackPrincipal,
        codexThreadId: String,
        now: Long,
    ): SlackCodexSession
    fun active(
        principal: SlackPrincipal,
        now: Long,
        idleTimeoutMillis: Long,
    ): SlackCodexSession?
    fun clearActive(principal: SlackPrincipal)
    fun touch(principal: SlackPrincipal, sessionId: Int, now: Long)
    fun failStaleProcessing(before: Long, now: Long): Int
}
