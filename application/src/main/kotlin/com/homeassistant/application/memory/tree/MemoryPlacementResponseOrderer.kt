package com.homeassistant.application.memory.tree

/** Orders a validated response so batch parents precede their children. */
object MemoryPlacementResponseOrderer {
    fun order(
        response: MemoryPlacementResponse,
        inputMemoryIds: List<Int>,
    ): MemoryPlacementResponse {
        val decisionsByMemoryId = response.decisions.associateBy { it.memoryId }
        val inputMemoryIdSet = inputMemoryIds.toSet()
        val ordered = mutableListOf<MemoryPlacementDecision>()
        val visiting = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()

        fun visit(memoryId: Int) {
            if (memoryId in visited) return
            check(visiting.add(memoryId)) {
                "Placement response must be validated before ordering"
            }
            val decision = decisionsByMemoryId.getValue(memoryId)
            val parentId = decision.parentId
            if (parentId != null && parentId in inputMemoryIdSet) visit(parentId)
            visiting.remove(memoryId)
            visited += memoryId
            ordered += decision
        }

        inputMemoryIds.forEach(::visit)
        return response.copy(decisions = ordered)
    }
}
