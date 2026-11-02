package com.homeassistant.domain.episode

import com.homeassistant.domain.identity.UserId

/** The users allowed to retrieve a source record or canonical episode. */
data class EpisodeAccess(
    val visibility: EpisodeVisibility,
    val allowedUserIds: Set<String> = emptySet(),
) {
    init {
        require(allowedUserIds.none(String::isBlank)) { "allowed userIds must not be blank" }
        when (visibility) {
            EpisodeVisibility.PUBLIC -> require(allowedUserIds.isEmpty()) {
                "PUBLIC access must not have an allow list"
            }
            EpisodeVisibility.RESTRICTED -> require(allowedUserIds.isNotEmpty()) {
                "RESTRICTED access requires at least one userId"
            }
        }
    }

    fun isVisibleTo(requester: UserId): Boolean =
        visibility == EpisodeVisibility.PUBLIC || requester.value in allowedUserIds

    companion object {
        val PUBLIC = EpisodeAccess(EpisodeVisibility.PUBLIC)

        fun restricted(userIds: Collection<UserId>): EpisodeAccess =
            EpisodeAccess(
                visibility = EpisodeVisibility.RESTRICTED,
                allowedUserIds = userIds.mapTo(linkedSetOf(), UserId::value),
            )

        /** PUBLIC is the neutral element; restricted inputs are intersected to avoid widening access. */
        fun intersection(accesses: Collection<EpisodeAccess>): EpisodeAccess {
            require(accesses.isNotEmpty()) { "at least one access scope is required" }
            val restricted = accesses.filter { it.visibility == EpisodeVisibility.RESTRICTED }
            if (restricted.isEmpty()) return PUBLIC
            val viewers = restricted
                .map { it.allowedUserIds }
                .reduce(Set<String>::intersect)
            require(viewers.isNotEmpty()) { "source access scopes do not share an allowed user" }
            return EpisodeAccess(EpisodeVisibility.RESTRICTED, viewers)
        }
    }
}
