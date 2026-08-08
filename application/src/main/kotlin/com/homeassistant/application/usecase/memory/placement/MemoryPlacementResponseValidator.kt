package com.homeassistant.application.usecase.memory.placement

import com.homeassistant.application.port.input.memory.placement.MemoryPlacementException
import com.homeassistant.application.port.output.memory.placement.MemoryPlacementInput
import com.homeassistant.application.port.output.memory.placement.MemoryPlacementResponse

/** Validates a complete extractor response without transforming or ordering it. */
object MemoryPlacementResponseValidator {
    fun validate(
        input: MemoryPlacementInput,
        response: MemoryPlacementResponse,
        selectableMemoryIds: Set<Int>,
    ) {
        val inputMemoryIds = input.memories.map { it.id }
        val inputMemoryIdSet = inputMemoryIds.toSet()
        checkDecisionCount(inputMemoryIds, response)
        checkDecisionIdsAreUnique(response)
        checkDecisionIdsMatchInput(inputMemoryIdSet, response)
        checkNoSelfParent(response)
        checkParentsAreSelectable(response, inputMemoryIdSet, selectableMemoryIds)
        checkParentGraphIsAcyclic(response, inputMemoryIdSet)
    }

    private fun checkDecisionCount(
        inputMemoryIds: List<Int>,
        response: MemoryPlacementResponse,
    ) {
        if (response.decisions.size != inputMemoryIds.size) {
            throw MemoryPlacementException(
                "Placement response must contain exactly one decision per input memory",
            )
        }
    }

    private fun checkDecisionIdsAreUnique(response: MemoryPlacementResponse) {
        val decisionIds = response.decisions.map { it.memoryId }
        if (decisionIds.size != decisionIds.toSet().size) {
            throw MemoryPlacementException("Placement response contains duplicate memory ids")
        }
    }

    private fun checkDecisionIdsMatchInput(
        inputMemoryIds: Set<Int>,
        response: MemoryPlacementResponse,
    ) {
        val decisionIds = response.decisions.map { it.memoryId }.toSet()
        val missingIds = inputMemoryIds - decisionIds
        val unexpectedIds = decisionIds - inputMemoryIds
        if (missingIds.isNotEmpty() || unexpectedIds.isNotEmpty()) {
            throw MemoryPlacementException(
                "Placement response ids do not match input: missing=$missingIds unexpected=$unexpectedIds",
            )
        }
    }

    private fun checkNoSelfParent(response: MemoryPlacementResponse) {
        response.decisions.forEach { decision ->
            if (decision.parentId == decision.memoryId) {
                throw MemoryPlacementException(
                    "A memory cannot be its own parent: memoryId=${decision.memoryId}",
                )
            }
        }
    }

    private fun checkParentsAreSelectable(
        response: MemoryPlacementResponse,
        inputMemoryIds: Set<Int>,
        selectableMemoryIds: Set<Int>,
    ) {
        response.decisions.forEach { decision ->
            val parentId = decision.parentId ?: return@forEach
            if (parentId !in selectableMemoryIds && parentId !in inputMemoryIds) {
                throw MemoryPlacementException(
                    "Placement selected an unavailable parent: parentId=$parentId memoryId=${decision.memoryId}",
                )
            }
        }
    }

    private fun checkParentGraphIsAcyclic(
        response: MemoryPlacementResponse,
        inputMemoryIds: Set<Int>,
    ) {
        val parentByChild = response.decisions.associate { it.memoryId to it.parentId }
        val visiting = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()

        fun visit(memoryId: Int) {
            if (memoryId in visited) return
            if (!visiting.add(memoryId)) {
                throw MemoryPlacementException(
                    "Placement response contains a cycle involving memoryId=$memoryId",
                )
            }
            val parentId = parentByChild.getValue(memoryId)
            if (parentId != null && parentId in inputMemoryIds) visit(parentId)
            visiting.remove(memoryId)
            visited += memoryId
        }

        inputMemoryIds.forEach(::visit)
    }
}
