package com.homeassistant.application.port.input.topic

import com.homeassistant.domain.identity.UserId

/** Describes a Topic for one viewer from only the memories that viewer can read. */
fun interface DescribeTopic {
    /**
     * [topicId] described for [userId], reusing the stored text until the readable memories changed enough, or
     * [TopicDescriptionResult.Hidden].
     * @throws com.homeassistant.domain.identity.UserAccessDeniedException when [userId] may not read.
     */
    suspend fun describe(userId: UserId, topicId: Int): TopicDescriptionResult
}

sealed interface TopicDescriptionResult {
    data class Described(val text: String) : TopicDescriptionResult

    /** The Topic does not exist or the viewer can read none of it; the two are not told apart. */
    data object Hidden : TopicDescriptionResult
}
