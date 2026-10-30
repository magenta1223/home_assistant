package com.homeassistant.domain.identity

/** A password as the user typed it. It only passes through login and registration and is never stored. */
class Password(val value: String) {
    init {
        require(value.length >= MIN_LENGTH) { "password must be at least $MIN_LENGTH characters" }
    }

    override fun toString(): String = "Password(***)"

    companion object {
        const val MIN_LENGTH = 8
    }
}
