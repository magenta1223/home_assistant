package com.homeassistant.core.memory

import kotlinx.serialization.Serializable

/** Top-level cognitive category for a long-lived household memory. */
@Serializable
enum class MemoryKind { SEMANTIC, EPISODIC, PROCEDURAL }

object MemoryKindCodes {
    const val SEMANTIC = "SEMANTIC"
    const val EPISODIC = "EPISODIC"
    const val PROCEDURAL = "PROCEDURAL"
}

/** Kind-scoped subtype. Do not model subtypes as independent of MemoryKind. */
sealed interface MemorySubtype {
    val kind: MemoryKind
    val code: String
}

@Serializable
enum class SemanticMemorySubtype : MemorySubtype {
    PROFILE,
    PREFERENCE,
    RELATIONSHIP,
    STATE,
    LOCATION,
    REFERENCE,
    DECISION,
    CONSTRAINT;

    override val kind: MemoryKind = MemoryKind.SEMANTIC
    override val code: String = name
}

@Serializable
enum class EpisodicMemorySubtype : MemorySubtype {
    CONVERSATION,
    EVENT,
    TRANSACTION,
    APPOINTMENT,
    CHANGE,
    MILESTONE,
    OBSERVATION;

    override val kind: MemoryKind = MemoryKind.EPISODIC
    override val code: String = name
}

@Serializable
enum class ProceduralMemorySubtype : MemorySubtype {
    ROUTINE,
    CHECKLIST,
    INSTRUCTION,
    RULE,
    RECIPE,
    TROUBLESHOOTING,
    TEMPLATE;

    override val kind: MemoryKind = MemoryKind.PROCEDURAL
    override val code: String = name
}

data class MemoryClassification(
    val kind: MemoryKind,
    val subtype: MemorySubtype,
) {
    init {
        require(kind == subtype.kind) { "Memory subtype ${subtype.code} does not belong to kind $kind" }
    }

    val subtypeCode: String get() = subtype.code

    companion object {
        fun parse(kind: String, subtype: String): MemoryClassification =
            parse(MemoryKind.valueOf(kind.trim().uppercase()), subtype)

        fun parse(kind: MemoryKind, subtype: String): MemoryClassification {
            val code = subtype.trim().uppercase()
            val parsedSubtype = when (kind) {
                MemoryKind.SEMANTIC -> SemanticMemorySubtype.valueOf(code)
                MemoryKind.EPISODIC -> EpisodicMemorySubtype.valueOf(code)
                MemoryKind.PROCEDURAL -> ProceduralMemorySubtype.valueOf(code)
            }
            return MemoryClassification(kind, parsedSubtype)
        }
    }
}

/** Tracks the review state of machine-generated candidates before they become confirmed memory. */
enum class CandidateStatus { PENDING, APPROVED, REJECTED }
