package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.group.Group

/** A [GroupChange] lost to a concurrent change: an expected Group revision no longer matches. */
class GroupChangeConflict(message: String) : IllegalStateException(message)

/** Stored Groups. Trusted internal metadata only; this is not an ACL-filtered user projection. */
interface GroupRepository {
    /**
     * Every Group with its revision, centroid and the Component vectors it used, read at one point in time.
     * It has no condition: placement takes the nearest centroid of every Group, and split and merge score the whole
     * partition, so every Group is an input to the decision.
     */
    fun findSnapshot(): GroupSnapshot

    /**
     * Every Group holding a Component [userId] can read, with only those Components and its stored centroid, for search.
     * A Group with no such Component is left out, since nothing in it could be returned.
     */
    fun findVisibleTo(userId: UserId): List<StoredGroup>

    /**
     * Atomically checks revisions, validates the exact partition and replaces membership and centroids; returns the
     * resulting Groups in [GroupChange.resultingGroups] order. A change whose operation key was saved before returns
     * the Groups as that change saved them.
     * @throws GroupChangeConflict when an expected Group revision changed.
     */
    fun save(change: GroupChange): List<Group>
}
