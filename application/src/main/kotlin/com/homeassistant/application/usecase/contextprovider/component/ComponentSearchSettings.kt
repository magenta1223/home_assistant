package com.homeassistant.application.usecase.contextprovider.component

/**
 * How wide each level of [ComponentSearcher] looks and how many it keeps. [groupCandidates] caps the nearest
 * [groupPool] Groups plus their neighbors, as xMemory's Stage I pool of 20 does.
 */
data class ComponentSearchSettings(
    val groupPool: Int = 5,
    val groupCandidates: Int = 20,
    val groupLimit: Int = 3,
    val componentPool: Int = 20,
    val componentLimit: Int = 5,
) {
    init {
        require(groupPool > 0 && groupLimit > 0 && componentPool > 0 && componentLimit > 0)
        require(groupCandidates >= groupPool)
    }
}