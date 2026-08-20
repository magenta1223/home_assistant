# Memory conversation use case

`HandleMemoryConversation`은 등록된 사용자의 질문 한 건을 memory context와 Codex conversation turn으로
처리한다. 채널 사용자 등록은 다루지 않으며 request idempotency와 10분 idle session lease를 소유한다.

## 질문 처리와 session 선택

```mermaid
sequenceDiagram
    participant Workflow as MemoryAnswerWorkflow
    participant Conversation as HandleMemoryConversation
    participant Sessions as MemoryConversationSessionStore
    participant Context as MemoryConversationContextSource
    participant Prompt as MemoryConversationPromptBuilder
    participant Client as ConversationTurnClient

    Workflow->>Conversation: answer(request)
    Conversation->>Sessions: claimRequest(requestKey)
    alt 이미 처리 중이거나 처리됨
        Conversation->>Sessions: receipt(requestKey)
        Sessions-->>Conversation: 기존 receipt
        Conversation-->>Workflow: 기존 AnswerReady 또는 AlreadyHandled
    else 새 요청 claim
        Conversation->>Sessions: active(participant, idle=10분)
        Conversation->>Context: context(userId, question)
        alt direct memory match 없음
            Conversation->>Sessions: markAnswerReady(고정 no-match 답변)
            Conversation-->>Workflow: AnswerReady
        else context 존재
            Conversation->>Prompt: build(reference, question)
            alt 활성 session 없음
                Conversation->>Client: start(prompt)
                Client-->>Conversation: threadId callback
                Conversation->>Sessions: createAndActivate + attachSession
            else 활성 session 존재
                Conversation->>Client: resume(threadId, prompt)
            end
            alt turn 성공
                Conversation->>Sessions: touch + markAnswerReady
                Conversation-->>Workflow: AnswerReady
            else turn 실패
                Conversation->>Sessions: markFailed + clearActive
                Conversation-->>Workflow: Failed
            end
        end
    end
```

## 전달 완료

```mermaid
sequenceDiagram
    participant Inbound as Inbound adapter
    participant Conversation as HandleMemoryConversation
    participant Sessions as MemoryConversationSessionStore

    Inbound->>Conversation: markDelivered(requestKey, deliveryId)
    Conversation->>Sessions: markCompleted(requestKey, deliveryId, now)
```

## 규칙

- 10분 idle lease가 지난 thread는 재개하지 않고 다음 질문에서 새 thread를 시작한다.
- 같은 request key는 다시 Codex에 보내지 않는다.
- answer를 만들었지만 채널 전송 전인 `ANSWER_READY`는 동일 이벤트 재처리 시 기존 answer를 반환한다.
- memory match가 없으면 Codex를 호출하지 않고 고정된 no-match 답변을 반환한다.
- prompt의 memory reference는 신뢰하지 않는 데이터로 감싸며 그 안의 지시를 실행하지 않는다.
