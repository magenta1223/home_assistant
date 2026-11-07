package com.homeassistant.domain.access

import com.homeassistant.domain.identity.UserId

/**
 * Who may read a source record, an episode or a Component: an access control list with a single read permission,
 * granted to everyone when [visibility] is PUBLIC and only to [allowedUserIds] when it is RESTRICTED.
 */
data class AccessControl(
    val visibility: Visibility,
    val allowedUserIds: Set<String> = emptySet(),
) {
    init {
        require(allowedUserIds.none(String::isBlank)) { "allowed userIds must not be blank" }
        when (visibility) {
            Visibility.PUBLIC -> require(allowedUserIds.isEmpty()) {
                "PUBLIC access must not have an allow list"
            }
            Visibility.RESTRICTED -> require(allowedUserIds.isNotEmpty()) {
                "RESTRICTED access requires at least one userId"
            }
        }
    }

    fun isVisibleTo(requester: UserId): Boolean =
        visibility == Visibility.PUBLIC || requester.value in allowedUserIds

    /** True when everyone who can read [other] can also read this. */
    fun covers(other: AccessControl): Boolean =
        visibility == Visibility.PUBLIC ||
            (other.visibility == Visibility.RESTRICTED && allowedUserIds.containsAll(other.allowedUserIds))

    companion object {
        val PUBLIC = AccessControl(Visibility.PUBLIC)

        fun restricted(userIds: Collection<UserId>): AccessControl =
            AccessControl(
                visibility = Visibility.RESTRICTED,
                allowedUserIds = userIds.mapTo(linkedSetOf(), UserId::value),
            )

        /** PUBLIC is the neutral element; restricted inputs are intersected to avoid widening access. */
        fun intersection(accesses: Collection<AccessControl>): AccessControl {
            require(accesses.isNotEmpty()) { "at least one access scope is required" }
            val restricted = accesses.filter { it.visibility == Visibility.RESTRICTED }
            if (restricted.isEmpty()) return PUBLIC
            val viewers = restricted
                .map { it.allowedUserIds }
                .reduce(Set<String>::intersect)
            require(viewers.isNotEmpty()) { "source access scopes do not share an allowed user" }
            return AccessControl(Visibility.RESTRICTED, viewers)
        }
    }
}
