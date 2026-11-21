package com.homeassistant.domain.household

/** Identifies a household: the people who share their finances. */
@JvmInline
value class HouseholdId(val value: String) {
    init {
        require(value.isNotBlank()) { "householdId is required" }
    }
}
