package com.homeassistant.application.port.input.component

import com.homeassistant.domain.identity.UserId

/** Describes a Component for one viewer from only the episodes that viewer can read. */
fun interface DescribeComponent {
    /**
     * [componentId] described for [userId], reusing the stored text until the readable episodes changed enough, or
     * [ComponentDescriptionResult.Hidden].
     * @throws com.homeassistant.domain.identity.UserAccessDeniedException when [userId] may not read.
     */
    suspend fun describe(userId: UserId, componentId: Int): ComponentDescriptionResult
}

sealed interface ComponentDescriptionResult {
    data class Described(val text: String) : ComponentDescriptionResult

    /** The Component does not exist or the viewer can read none of it; the two are not told apart. */
    data object Hidden : ComponentDescriptionResult
}
