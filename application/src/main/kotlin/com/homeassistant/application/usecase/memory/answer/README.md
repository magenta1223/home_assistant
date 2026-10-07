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

## Answer context

`MemoryAnswerContextProvider`는 현재 ACL로 허용된 Memory 직접 검색 결과를 순서와 근거 그대로
전달한다. Memory 자식 확장은 제거했으며 Topic 검색·추가 탐색은 후속 작업이다. 검색 실패는 기존
오류 계약을 유지하고, conversation reference 형식·최대 8,000자·session 재사용 정책은 바꾸지 않는다.

```mermaid
sequenceDiagram
    participant Conversation as Memory conversation
    participant Context as MemoryAnswerContextProvider
    participant Search as MemorySearch

    Conversation->>Context: context(search request)
    Context->>Search: search(request)
    Search-->>Context: authorized direct matches
    Context-->>Conversation: direct matches and unchanged context matches
```

등록 안내 전송이 실패하면 inbound adapter가 `registrationPromptDeliveryFailed`를 호출한다. 동일한
pending key인 경우 저장 상태를 해제해 Slack 재전송 이벤트가 다시 등록 안내를 만들 수 있게 한다.
