package com.homeassistant.application.usecase.contextprovider

/** [reference] is the text given to the provider; [hasMatches] is false when it holds no episode. */
data class Context(
    val reference: String,
    val hasMatches: Boolean,
)