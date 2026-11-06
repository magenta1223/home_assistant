package com.homeassistant.application.usecase.contextprovider

/** One headed block of a reference. [entries] are never empty, so a [title] always has something under it. */
data class ContextSection(val title: String, val entries: List<String>) {
    init { require(entries.isNotEmpty()) }
}
