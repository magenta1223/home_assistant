package com.homeassistant.feature.chat.usecase

/** How a chat gives the provider the context it answers from. */
sealed interface ChatContextDelivery {
    /** Each question first retrieves a reference from [source], which is sent along with the question. */
    data class Prefetch(val source: ContextAssembler) : ChatContextDelivery

    /** The provider looks context up itself with the context tools, using a token [grants] issues for the turn. */
    data class OnRequest(val grants: ContextGrants) : ChatContextDelivery
}
