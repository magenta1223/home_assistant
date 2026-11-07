package com.homeassistant.application.usecase.contextprovider

/** [reference] is the text given to the provider; [hasMatches] is false when it holds no entry. */
data class ChatContext(
    val reference: String,
    val hasMatches: Boolean,
)