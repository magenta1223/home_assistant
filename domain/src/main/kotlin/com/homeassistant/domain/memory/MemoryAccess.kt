package com.homeassistant.domain.memory

import com.homeassistant.domain.identity.UserId

/** The users allowed to retrieve a source record or canonical memory. */
data class MemoryAccess(
    val visibility: MemoryVisibility,
    val allowedUserIds: Set<String> = emptySet(),
) {
    init {
        require(allowedUserIds.none(String::isBlank)) { "allowed userIds must not be blank" }
        when (visibility) {
            MemoryVisibility.PUBLIC -> require(allowedUserIds.isEmpty()) {
                "PUBLIC access must not have an allow list"
            }
            MemoryVisibility.RESTRICTED -> require(allowedUserIds.isNotEmpty()) {
                "RESTRICTED access requires at least one userId"
            }
        }
    }

    fun isVisibleTo(requester: UserId): Boolean =
        visibility == MemoryVisibility.PUBLIC || requester.value in allowedUserIds

    companion object {
        val PUBLIC = MemoryAccess(MemoryVisibility.PUBLIC)

        fun restricted(userIds: Collection<UserId>): MemoryAccess =
            MemoryAccess(
                visibility = MemoryVisibility.RESTRICTED,
                allowedUserIds = userIds.mapTo(linkedSetOf(), UserId::value),
            )

        /** PUBLIC is the neutral element; restricted inputs are intersected to avoid widening access. */
        fun intersection(accesses: Collection<MemoryAccess>): MemoryAccess {
            require(accesses.isNotEmpty()) { "at least one access scope is required" }
            val restricted = accesses.filter { it.visibility == MemoryVisibility.RESTRICTED }
            if (restricted.isEmpty()) return PUBLIC
            val viewers = restricted
                .map { it.allowedUserIds }
                .reduce(Set<String>::intersect)
            require(viewers.isNotEmpty()) { "source access scopes do not share an allowed user" }
            return MemoryAccess(MemoryVisibility.RESTRICTED, viewers)
        }
    }
}
