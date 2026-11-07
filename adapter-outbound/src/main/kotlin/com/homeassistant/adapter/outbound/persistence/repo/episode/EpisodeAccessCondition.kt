package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeViewerTable
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.or

/** The [AccessControl] rules as conditions on [EpisodeTable], so access is decided by the query instead of after it. */
internal object EpisodeAccessCondition {
    /** Episodes [userId] can read, as [AccessControl.isVisibleTo]. */
    fun visibleTo(userId: UserId): Op<Boolean> =
        public() or (EpisodeTable.id inSubQuery EpisodeViewerTable.select(EpisodeViewerTable.episodeId)
            .where { EpisodeViewerTable.userId eq userId.value })

    /** Episodes everyone who can read [access] can also read, as [AccessControl.covers]. */
    fun covering(access: AccessControl): Op<Boolean> = when (access.visibility) {
        Visibility.PUBLIC -> public()
        Visibility.RESTRICTED -> public() or (EpisodeTable.id inSubQuery viewedByAll(access.allowedUserIds))
    }

    private fun public(): Op<Boolean> = EpisodeTable.visibility eq Visibility.PUBLIC.name

    private fun viewedByAll(userIds: Set<String>) = EpisodeViewerTable.select(EpisodeViewerTable.episodeId)
        .where { EpisodeViewerTable.userId inList userIds }
        .groupBy(EpisodeViewerTable.episodeId)
        .having { EpisodeViewerTable.userId.count() eq userIds.size.toLong() }
}
