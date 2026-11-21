package com.homeassistant.domain.finance

/** Whose spending is shown. */
enum class SpendingScope {
    /** Only the requesting user's. */
    MINE,

    /** Every member's of the requesting user's household. */
    HOUSEHOLD,
}
