package com.homeassistant.core.memory

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryType(val groupCode: String) {
    // SEMANTIC
    PROFILE(GroupCodes.SEMANTIC),
    PREFERENCE(GroupCodes.SEMANTIC),
    RELATIONSHIP(GroupCodes.SEMANTIC),
    STATE(GroupCodes.SEMANTIC),
    LOCATION(GroupCodes.SEMANTIC),
    REFERENCE(GroupCodes.SEMANTIC),
    DECISION(GroupCodes.SEMANTIC),
    CONSTRAINT(GroupCodes.SEMANTIC),

    // EPISODIC
    CONVERSATION(GroupCodes.EPISODIC),
    EVENT(GroupCodes.EPISODIC),
    TRANSACTION(GroupCodes.EPISODIC),
    APPOINTMENT(GroupCodes.EPISODIC),
    CHANGE(GroupCodes.EPISODIC),
    MILESTONE(GroupCodes.EPISODIC),
    OBSERVATION(GroupCodes.EPISODIC),

    // PROCEDURAL
    ROUTINE(GroupCodes.PROCEDURAL),
    CHECKLIST(GroupCodes.PROCEDURAL),
    INSTRUCTION(GroupCodes.PROCEDURAL),
    RULE(GroupCodes.PROCEDURAL),
    RECIPE(GroupCodes.PROCEDURAL),
    TROUBLESHOOTING(GroupCodes.PROCEDURAL),
    TEMPLATE(GroupCodes.PROCEDURAL);

    val code: String get() = name
    
    companion object {
        private object GroupCodes {
            const val SEMANTIC = "SEMANTIC"
            const val EPISODIC = "EPISODIC"
            const val PROCEDURAL = "PROCEDURAL"
        }
    }
}
