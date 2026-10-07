package com.homeassistant.application.usecase.topic.placement

import com.homeassistant.application.port.output.topic.*

internal object TopicPlacementProposalValidator {
    fun prepare(input: TopicPlacementInput, proposal: TopicWriteBatch): TopicWriteBatch {
        validateCoverage(input, proposal)
        validateReferences(input, proposal)
        return scopeCreationKeys(input, proposal)
    }

    private fun validateCoverage(input: TopicPlacementInput, proposal: TopicWriteBatch) {
        val expectedIds = input.memories.map { it.id }.toSet()
        val assignedIds = proposal.memberships.flatMap { it.memoryIds }.toSet()
        require(assignedIds == expectedIds) { "placement must assign all and only input memories" }
        val usedCreationKeys = proposal.memberships.mapNotNull { (it.topic as? TopicReference.Created)?.creationKey }.toSet()
        require(usedCreationKeys == proposal.creations.map { it.creationKey }.toSet()) {
            "every new topic must receive an input memory and be declared"
        }
    }

    private fun validateReferences(input: TopicPlacementInput, proposal: TopicWriteBatch) {
        val allowed = input.candidates.map { TopicReference.Existing(it.id) }.toSet<TopicReference>() +
            proposal.creations.map { TopicReference.Created(it.creationKey) }
        proposal.memberships.forEach { require(it.topic in allowed) { "membership topic is outside supplied context" } }
        proposal.relations.forEach {
            require(it.first in allowed && it.second in allowed) { "relation topic is outside supplied context" }
            require(it.first != it.second) { "topic cannot relate to itself" }
        }
    }

    private fun scopeCreationKeys(input: TopicPlacementInput, proposal: TopicWriteBatch): TopicWriteBatch {
        val scope = "placement:${input.memories.map { it.id }.sorted().joinToString(",")}:"
        fun scoped(reference: TopicReference): TopicReference = when (reference) {
            is TopicReference.Existing -> reference
            is TopicReference.Created -> TopicReference.Created(scope + reference.creationKey)
        }
        return TopicWriteBatch(
            creations = proposal.creations.map { it.copy(creationKey = scope + it.creationKey) },
            memberships = proposal.memberships.map { it.copy(topic = scoped(it.topic)) },
            relations = proposal.relations.map { TopicRelationAddition(scoped(it.first), scoped(it.second)) },
        )
    }
}
