package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeViewerTable
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.or

/** The [EpisodeAccess] rules as conditions on [EpisodeTable], so access is decided by the query instead of after it. */
internal object EpisodeAccessCondition {
    /** Episodes [userId] can read, as [EpisodeAccess.isVisibleTo]. */
    fun visibleTo(userId: UserId): Op<Boolean> =
        public() or (EpisodeTable.id inSubQuery EpisodeViewerTable.select(EpisodeViewerTable.episodeId)
            .where { EpisodeViewerTable.userId eq userId.value })

    private fun public(): Op<Boolean> = EpisodeTable.visibility eq EpisodeVisibility.PUBLIC.name
}
