# Memory conversation use case

`HandleMemoryConversation`은 등록된 사용자의 질문 한 건을 memory context와 provider conversation으로
처리한다. 채널 사용자 등록은 다루지 않으며 request idempotency와 10분 idle session lease를 소유한다.

## 질문 처리와 session 선택

```mermaid
sequenceDiagram
    participant HTTP as Authenticated HTTP adapter
    participant Conversation as HandleMemoryConversation
    participant Sessions as MemoryConversationSessionStore
    participant Context as MemoryConversationContextSource
    participant Prompt as MemoryConversationPromptBuilder
    participant Gateway as ConversationGateway

    HTTP->>Conversation: answer(request)
    Conversation->>Sessions: claimRequest(requestKey)
    alt 이미 처리 중이거나 처리됨
        Conversation->>Sessions: receipt(requestKey)
        Sessions-->>Conversation: 기존 receipt
        Conversation-->>HTTP: 기존 AnswerReady 또는 AlreadyHandled
    else 새 요청 claim
        Conversation->>Sessions: lease(participant, idle=10분)
        alt idle lease 만료
            Sessions-->>Conversation: Expired(conversationId)
            Conversation->>Gateway: end(conversationId)
        end
        Conversation->>Context: context(userId, question)
        alt 읽을 수 있는 Topic 없음
            Conversation->>Sessions: markAnswerReady(고정 no-match 답변)
            Conversation-->>HTTP: AnswerReady
        else context 존재
            Conversation->>Prompt: build(reference, question)
            alt 활성 session 없음
                Conversation->>Gateway: begin()
                Gateway-->>Conversation: conversationId
                Conversation->>Sessions: createAndActivate + attachSession
            end
            Conversation->>Gateway: continueConversation(conversationId, prompt)
            alt turn 성공
                Conversation->>Sessions: touch + markAnswerReady
                Conversation-->>HTTP: AnswerReady
            else turn 실패
                Conversation->>Gateway: end(conversationId)
                Conversation->>Sessions: markFailed + clearActive
                Conversation-->>HTTP: Failed
            end
        end
    end
```

## Idle session 만료

```mermaid
sequenceDiagram
    participant Worker as MemoryConversationExpiryWorker
    participant Expiry as ExpireIdleMemoryConversations
    participant Sessions as MemoryConversationSessionStore
    participant Gateway as ConversationGateway

    loop 짧은 주기
        Worker->>Expiry: execute()
        Expiry->>Sessions: expireIdle(now - 10분)
        Sessions-->>Expiry: 만료되어 비활성화된 sessions
        loop 각 session
            Expiry->>Gateway: end(conversationId)
        end
    end
```

## 규칙

- 활성 session은 scope, participant, application user ID가 모두 일치할 때만 임대한다.
- 10분 idle lease가 지난 conversation은 즉시 비활성화하고 provider 자원을 해제하며 다시 재개하지 않는다.
- 같은 request key는 다시 conversation provider에 보내지 않는다.
- HTTP answer를 `ANSWER_READY`로 저장하고 동일 request 재처리 시 기존 answer를 반환한다. 전송 완료 callback은 없다.
- context는 `SearchTopics`로 찾은 Topic마다 한 블록이며, 사용자가 읽을 수 있는 Memory만 담는다(최대 8,000자).
- 읽을 수 있는 Topic이 없으면 conversation provider를 호출하지 않고 고정된 no-match 답변을 반환한다.
- prompt의 memory reference는 신뢰하지 않는 데이터로 감싸며 그 안의 지시를 실행하지 않는다.
- memory conversation use case는 session 상태에 따라 conversation 생성·재사용·종료 시점을 결정한다.
- `ConversationGateway.begin()`은 prompt 없이 ID를 만들고, 질문 prompt는
  `continueConversation(conversationId, prompt)`에만 전달한다.
- provider 실패 시 해당 conversation을 종료하고 active session과 request 처리를 실패 상태로 정리한다.

대화 저장소의 코드 이름은 MemoryConversation으로 통일했다. HTTP가 이미 사용하는 물리 테이블과
컬럼 이름은 기존 매핑을 유지해 활성 session과 request receipt를 보존한다. 이는 Slack 연결 기능이 아니다.
