package com.homeassistant.domain.topic

/** A search grouping of existing memories; membership never changes a memory or its access. */
data class Topic(
    val id: Int,
    val description: String,
    val memories: Map<String, List<Int>>,
) {
    init {
        require(id > 0) { "topic id must be positive" }
        require(description.isNotBlank()) { "topic description is required" }
        memories.forEach { (category, memoryIds) ->
            require(category.isNotBlank()) { "topic category is required" }
            require(memoryIds.isNotEmpty()) { "topic category must contain memories" }
            require(memoryIds.all { it > 0 }) { "memory ids must be positive" }
            require(memoryIds.distinct().size == memoryIds.size) { "category memory references must be unique" }
        }
    }
}
