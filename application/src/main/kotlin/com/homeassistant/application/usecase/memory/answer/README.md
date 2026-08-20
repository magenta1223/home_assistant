# Memory answer workflow

`MemoryAnswerWorkflowService`는 채널에 독립적인 사용자 등록 및 memory answer 진입점이다. Slack은
이 input port만 호출하며 `UserRegistry`, pending question, `MemoryConversation`을 직접 조합하지 않는다.

## 질문 접수

```mermaid
sequenceDiagram
    participant Inbound as Inbound adapter
    participant Workflow as MemoryAnswerWorkflowService
    participant Registry as UserRegistry
    participant Pending as PendingRegistrationQuestionStore
    participant Conversation as MemoryConversation

    Inbound->>Workflow: receive(request)
    Workflow->>Registry: find(identity)
    alt 등록된 사용자
        Registry-->>Workflow: RegisteredUser
        Workflow->>Conversation: answer(request + userId)
        Conversation-->>Workflow: AnswerReady / AlreadyHandled / Failed
        Workflow-->>Inbound: MemoryAnswerResult
    else 미등록 사용자
        Registry-->>Workflow: null
        Workflow->>Pending: rememberFirst(request, now)
        alt 최초 질문 저장 성공
            Workflow-->>Inbound: RegistrationRequired
        else 이미 대기 질문 존재
            Workflow-->>Inbound: RegistrationPending
        end
    end
```

## 등록 완료와 최초 질문 재개

```mermaid
sequenceDiagram
    actor User
    participant Inbound as Inbound adapter
    participant Workflow as MemoryAnswerWorkflowService
    participant Domain as RegisteredUser
    participant Pending as PendingRegistrationQuestionStore
    participant Registry as UserRegistry
    participant Conversation as MemoryConversation

    User->>Inbound: 표시 이름 제출
    Inbound->>Workflow: validateRegistration(name)
    Workflow->>Domain: normalizeDisplayName(name)
    Domain-->>Workflow: valid name
    Inbound->>Workflow: completeRegistration(identity, name)
    Workflow->>Pending: find(identity)
    Pending-->>Workflow: 최초 질문
    Workflow->>Registry: register(identity, name)
    Registry-->>Workflow: RegisteredUser
    Workflow-->>Inbound: onRegistered notice
    Workflow->>Conversation: answer(최초 질문 + userId)
    Conversation-->>Workflow: MemoryConversationResult
    Workflow->>Pending: remove(identity)
    Workflow-->>Inbound: Completed(replyKey, answer result)
```

## Answer context 확장

`MemoryAnswerContextProvider`는 일반 semantic search 결과를 seed로 사용하고, 보이는 child memory만
제한적으로 재검색한다. 직접 검색 결과 자체는 바꾸지 않고 Codex reference context만 확장한다.

```mermaid
sequenceDiagram
    participant Conversation as Memory conversation
    participant Context as MemoryAnswerContextProvider
    participant Search as MemorySearch
    participant Reader as MemoryReader
    participant Index as SemanticMemoryIndexSearcher

    Conversation->>Context: context(search request)
    Context->>Search: search(request)
    Search-->>Context: direct seed matches
    alt seed 또는 visible child가 없음
        Context-->>Conversation: direct matches only
    else child 후보 존재
        Context->>Reader: getMemories(userId)
        Reader-->>Context: visible memory tree
        Context->>Index: child candidate 범위 semantic search
        Index-->>Context: ranked children
        Context-->>Conversation: direct + bounded child context
    end
```

등록 안내 전송이 실패하면 inbound adapter가 `registrationPromptDeliveryFailed`를 호출한다. 동일한
pending key인 경우 저장 상태를 해제해 Slack 재전송 이벤트가 다시 등록 안내를 만들 수 있게 한다.
