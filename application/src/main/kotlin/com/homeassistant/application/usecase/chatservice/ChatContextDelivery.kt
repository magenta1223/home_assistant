package com.homeassistant.application.usecase.chatservice

import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.application.usecase.contexttool.ContextGrants

/** How a chat gives the provider the context it answers from. */
sealed interface ChatContextDelivery {
    /** Each question first retrieves a reference from [source], which is sent along with the question. */
    data class Prefetch(val source: ContextSource) : ChatContextDelivery

    /** The provider looks context up itself with the context tools, using a token [grants] issues for the turn. */
    data class OnRequest(val grants: ContextGrants) : ChatContextDelivery
}
