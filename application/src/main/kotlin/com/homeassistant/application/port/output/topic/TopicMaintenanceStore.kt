package com.homeassistant.application.port.output.topic

import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicRelation

/**
 * Trusted ingestion/maintenance only. Reads include mixed-access metadata and must never be used
 * as user-facing projections or answer context. This port does not grant end users mutation access.
 */
interface TopicMaintenanceStore {
    fun readForMaintenance(topicIds: Collection<Int>): List<Topic>

    /** Returns relations incident to either endpoint in topicIds. */
    fun relationsForMaintenance(topicIds: Collection<Int>): Set<TopicRelation>

    /**
     * Atomically creates topics and adds references/relations without replacing existing membership.
     * Stable creation keys reuse the same topic; conflicting descriptions are rejected.
     * Repeated memberships and reverse relations are harmless. Missing references fail the batch.
     * Implementations own the transaction and must not call a model or modify Memory/Source state.
     */
    fun apply(batch: TopicWriteBatch): TopicWriteResult
}
