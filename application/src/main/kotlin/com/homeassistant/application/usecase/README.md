# Application use cases

이 디렉터리는 input port를 구현하고 domain 규칙과 output port를 조합하는 기술 중립적인
application orchestration을 담는다. HTTP, Slack, SQLite, Codex, Qdrant 같은 기술 이름과
payload 형식은 여기에서 직접 다루지 않는다.

## 패키지 지도

| 패키지 | 책임 | 상세 문서 |
|---|---|---|
| `identity` | conversation identity를 application user로 해석하고 등록·인가한다. | [identity](identity/README.md) |
| `memory/analysis` | source record를 분석해 canonical memory로 저장하고 후처리를 요청한다. | [analysis](memory/analysis/README.md) |
| `memory/answer` | 사용자 등록과 memory-backed answer의 채널 독립 workflow를 조정한다. | [answer](memory/answer/README.md) |
| `memory/conversation` | 질문 멱등성, 10분 세션 lease, context와 Codex turn을 관리한다. | [conversation](memory/conversation/README.md) |
| `memory/placement` | 새 memory를 기존 memory tree에 배치한다. | [placement](memory/placement/README.md) |
| `memory/search` | 사용자에게 보이는 memory만 semantic search한다. | [search](memory/search/README.md) |
| `memory/write` | proposal을 원자적으로 저장하고 indexing outbox를 처리한다. | [write](memory/write/README.md) |

## 전체 memory 쓰기 흐름

```mermaid
sequenceDiagram
    actor Caller
    participant Analysis as MemoryAnalysisService
    participant Access as UserAccessPolicy
    participant Reference as SourceReferenceInterpreter
    participant Sources as SourceRecordRepository
    participant Extractor as MemoryExtractor
    participant Writer as MemoryProposalsPersister
    participant DB as CanonicalMemoryBatchWriter
    participant Indexing as MemoryIndexingOutboxProcessor
    participant Placement as MemoryPlacement

    Caller->>Analysis: execute(request)
    Analysis->>Access: 작성자와 audience 인가 확인
    opt PDF 또는 이미지 reference
        Analysis->>Reference: 페이지/이미지별 근거 해석
        Reference-->>Analysis: 원본에 연결할 source record content
    end
    Analysis->>Sources: source record 저장 및 분석 대상 결정
    Analysis->>Extractor: 분석 대상에서 proposal 추출
    Analysis->>Writer: proposal 저장 요청
    Writer->>DB: memory + evidence + outbox 원자적 commit
    DB-->>Writer: canonical memories
    Writer-->>Analysis: saved memories
    Analysis->>Indexing: 처리 가능한 outbox projection
    Analysis->>Placement: 저장된 memory tree 배치
    Analysis-->>Caller: MemoryAnalysisResult
```

Canonical database commit까지가 요청 성공의 기준이다. Indexing과 tree placement는 commit 이후의
후처리이므로 실패하면 기록만 남기고 저장된 canonical memory를 되돌리지 않는다.

채널에서 시작된 지식 주입은 `KnowledgeInjectionWorkflow`가 conversation identity를 등록 사용자로
해석한 뒤 동일한 `MemoryAnalysis` 쓰기 흐름에 진입한다. 채널 adapter는 application user ID를 직접
결정하지 않는다.

## 전체 Slack memory 응답 흐름

```mermaid
sequenceDiagram
    actor User
    participant Inbound as Slack inbound adapter
    participant Workflow as MemoryAnswerWorkflow
    participant Registry as UserRegistry
    participant Pending as PendingRegistrationQuestionStore
    participant Conversation as MemoryConversation
    participant Search as MemorySearch
    participant Lifecycle as ConversationThreadLifecycle
    participant Executor as ConversationTurnExecutor

    User->>Inbound: DM
    Inbound->>Workflow: receive(MemoryAnswerRequest)
    Workflow->>Registry: find(ConversationIdentity)
    alt 등록되지 않은 사용자
        Workflow->>Pending: rememberFirst(request)
        Workflow-->>Inbound: RegistrationRequired 또는 RegistrationPending
        Inbound-->>User: 등록 UI
        User->>Inbound: 이름 제출
        Inbound->>Workflow: completeRegistration(request)
        Workflow->>Registry: register(request)
        Workflow->>Pending: find(identity)
        Workflow->>Conversation: answer(보관된 최초 질문)
        Workflow->>Pending: remove(identity)
    else 등록된 사용자
        Workflow->>Conversation: answer(request + userId)
    end
    Conversation->>Search: 허용된 memory context 조회
    opt 활성 session 없음
        Conversation->>Lifecycle: create()
        Lifecycle-->>Conversation: threadId
    end
    Conversation->>Executor: execute(threadId, prompt)
    Executor-->>Conversation: answer
    Conversation-->>Workflow: AnswerReady
    Workflow-->>Inbound: AnswerReady
    Inbound-->>User: Slack message
    Inbound->>Workflow: markDelivered(requestKey, deliveryId)
```

활성 conversation은 scope, participant, application user ID로 격리된다. 10분 동안 사용되지 않은
session은 background expiry 흐름이 비활성화하고 Codex thread 구독을 해제한다. Memory conversation
use case가 thread 생성·재사용·종료 시점을 결정하며, Codex adapter는 각 turn의 구조화된 `answer`
응답만 application에 반환한다.

Slack은 application result를 UI로 표현할 뿐이며 사용자 registry, pending question, authorization,
idempotency와 session expiry를 소유하지 않는다.

## 문서 유지 규칙

- 실제 use case 구현이 있는 각 leaf package에는 `README.md`를 둔다.
- 각 README에는 최소 하나의 Mermaid `sequenceDiagram`으로 정상 흐름과 중요한 분기를 표현한다.
- input/output port나 호출 순서가 바뀌면 같은 변경에서 해당 README를 갱신한다.
- diagram participant에는 구현 기술보다 application 역할 또는 port 이름을 사용한다.
