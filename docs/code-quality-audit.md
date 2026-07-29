# Code Quality Audit

## Rules applied

- Every production and test Kotlin source file is limited to 300 lines.
- Cross-component behavior is expressed through an interface.
- Concrete behavioral implementations are `internal` or `private`.
- Module factories construct implementations and return their narrowest useful interface.
- DTOs, value objects, enums, sealed results, exceptions, serialization models, and stateless pure
  utilities do not receive artificial interfaces or factories.
- SRP is evaluated by reason to change, not by method count. A persistence adapter may persist one
  aggregate through several operations without becoming several unrelated responsibilities.

## Findings and resolution

### File boundaries

The oversized NLP and route tests mixed scenarios with reusable fixtures. They were split by
behavior, and `CodeQualityBoundaryTest` now scans all module source trees and rejects files over 300
lines. It also rejects newly introduced public concrete behavioral classes.

### Composition and implementation hiding

The application previously constructed NLP, vector, answer, import, and Slack implementations
directly. `ApplicationServicesFactory` is now the composition boundary, while domain, NLP, and Slack
factories return ports and keep their implementations hidden. `TopicAnalysisUseCase` is an
interface, and household access policies, no-op adapters, LLM backends, repositories, and runtime
implementations are no longer exposed as concrete cross-module dependencies.

### Interface segregation

The former broad dependencies were narrowed:

- `MemoryStore` and `TopicAnalysisStore` expose command and query sub-ports for consumers that need
  only one side.
- Slack file, message, modal, Kakao, and interaction clients are separate ports.
- Topic answering and indexing depend on query/search ports rather than concrete repositories.
- Kakao import, topic analysis, Slack runtime, conversation handling, identity lookup, prompts,
  queues, review sessions, and Codex parsing are accessed through interfaces and factories.

### Single responsibility

The following responsibilities were separated:

- Application lifecycle from dependency graph construction.
- Slack Socket Mode lifecycle from Kakao, confirmation, and conversation listener registration.
- Slack file metadata, authenticated download, text decoding, message delivery, and modal delivery.
- Codex process orchestration from JSONL event parsing.
- Slack conversation orchestration from prompt building and serial task scheduling.
- Topic and memory lifecycle operations from vector indexing and retry coordination.
- Topic-analysis wire models and validation from prompt policy.
- Qdrant vector behavior and protocol mapping from HTTP transport.
- Route registration from unused response models.

`MemoryRepository` and `TopicAnalysisRepository` remain cohesive persistence adapters for their
respective aggregates. Their public dependencies are segregated ports, and their implementations
are internal. Splitting each database operation into another class would add indirection without a
separate reason to change.

`LlmTopicAnalyzer` remains one analysis orchestrator: chunking and merging are parts of the same
analysis policy. Output schema validation and prompt policy are separate components/files. Likewise,
tool schema declaration and tool-call dispatch remain one domain-tool adapter after indexing was
extracted.

## Enforced end state

1. Kotlin files are at most 300 lines.
2. Behavioral implementation classes are not public.
3. Cross-module construction uses factories that return interfaces.
4. Application startup owns only Ktor configuration, service lifecycle, and route installation.
5. Transport, parsing, orchestration, persistence, indexing, and retry policies have distinct
   reasons to change.
