package com.homeassistant.constants

enum class ContextType(val value: String) {
    RECENT("recent"), QUERY("query"), SIMILAR("similar");
    companion object { fun fromValue(v: String) = entries.firstOrNull { it.value == v } }
}
