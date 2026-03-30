package com.homeassistant.core.constants

sealed class SharedStatus(val sqlValue: Int) {
    object PRIVATE : SharedStatus(0)
    object SHARED  : SharedStatus(1)

    companion object {
        const val INPUT_PREFIX     = "공유 "
        const val INPUT_PREFIX_LEN = 3
        const val FILTER_LABEL     = "공유"

        fun fromBoolean(shared: Boolean): SharedStatus =
            if (shared) SHARED else PRIVATE
    }
}