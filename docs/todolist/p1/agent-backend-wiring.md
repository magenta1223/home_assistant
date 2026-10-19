# Agent backend(Codex·Claude) wiring

- 상태: VERIFY — 실제 서버에서 두 backend 동작 확인 남음
- 우선순위: P1
- 선행 작업: `integration-llm` 모듈(AgentBackend, AgentClientFactory, Codex·Claude 구현) 커밋

## 문제

`integration-llm`은 Codex와 Claude Code CLI를 같은 계약으로 제공하지만 아직 어디에도 연결되지
않았다. adapter-outbound와 app은 여전히 `integration-codex`를 직접 사용한다.

- `MemoryExtractor`, `CodexTopicAssignmentGenerator`, `CodexSourceReferenceInterpreter`가
  `com.homeassistant.codex.completion.CompletionClient`·`CodexImage`를 import하고, 각 Factory가
  `CodexCompletionClientFactory`를 직접 호출한다.
- 대화는 adapter-outbound의 `ConversationGatewayFactory`가 `CodexAppServerFactory`로
  `CodexConversationGateway`를 만든다. 같은 구현이 이제 `integration-llm`에도 있다.
- backend 선택 설정이 없고 timeout 설정 이름이 `CODEX_TIMEOUT_SECONDS`다.

## 목표

- 조립 지점(app)에서 `AGENT_BACKEND` 값 하나로 completion과 conversation backend를 고른다.
- adapter-outbound의 completion 사용 adapter는 `com.homeassistant.llm.completion.CompletionClient`만
  의존하고 backend를 알지 않는다.
- 대화는 `AgentClientFactory.conversation`이 돌려주는 application `ConversationGateway`를 그대로 쓴다.
- 기본값은 `codex`로 두어 설정을 바꾸기 전까지 현재 동작을 유지한다.
- 전환 후 `integration-codex`와 중복·미사용 코드를 제거한다.

## 의존 방향

```text
app ──> adapter-outbound ──> integration-llm ──> application
 └────────────────────────> integration-llm   (AgentBackend, AgentClientFactory)
```

app만 `AgentBackend`를 알고, adapter-outbound는 `CompletionClient` 타입만 안다.

## 구현 순서

1. 설정
   - `AppConfig`에 `ENV_VAR_AGENT_BACKEND = "AGENT_BACKEND"`(값 `codex`|`claude`, 기본 `codex`)를 추가한다.
   - `CODEX_TIMEOUT_SECONDS`/`DEFAULT_CODEX_TIMEOUT_SECONDS`를 `AGENT_TIMEOUT_SECONDS`/
     `DEFAULT_AGENT_TIMEOUT_SECONDS`로 바꾼다. 로컬 `.env`도 함께 바꾼다(저장소의 배포 스크립트·문서에는
     이 변수가 없다).
   - 알 수 없는 backend 값은 시작 시 실패시킨다(조용히 codex로 대체하지 않는다).
2. adapter-outbound completion 사용처
   - `build.gradle.kts`: `integration-codex` → `integration-llm`.
   - `MemoryExtractorFactory`, `TopicAssignmentGeneratorFactory`, `SourceReferenceInterpreterFactory`의
     `create()`를 `create(client: CompletionClient)`로 바꾼다.
   - `CodexImage` → `CompletionImage`로 import를 교체한다.
3. app 조립 (`ApplicationServicesFactory`)
   - `AgentBackend`를 한 번 읽고 `AgentClientFactory.completion(backend)`로 만든 client 하나를 세
     Factory에 넘긴다.
   - `ConversationGatewayFactory.create(timeout)` 대신 `AgentClientFactory.conversation(backend, timeout)`을
     쓴다. null이면 지금처럼 대화 기능을 끈다. 로그 문구의 "Codex"는 backend 이름으로 바꾼다.
   - `app/build.gradle.kts`에 `integration-llm` 의존을 추가한다.
4. 제거 (아래 "불필요해지는 것")
5. 검증
   - `./gradlew test` 전체.
   - `AGENT_BACKEND=codex`로 서버 기동 후 지식 주입 1건, 대화 2턴(앞 턴 기억 확인).
   - Claude CLI 재로그인 후 `AGENT_BACKEND=claude`로 같은 확인과 이미지·PDF 주입 1건.

## 불필요해지는 것

| 대상 | 이유 | 처리 |
|---|---|---|
| `integration-codex` 모듈 전체와 `settings.gradle.kts` include | `integration-llm/codex`로 복사됨 | 삭제 |
| `integration-codex`의 `rpcclient/`, `subprocess/`, `StdioFixture.java` | 현재도 어디서도 쓰지 않음 | 모듈과 함께 삭제 (복사하지 않았음) |
| adapter-outbound `CodexConversationGateway` + 테스트 | `integration-llm`에 같은 구현이 있음 | 삭제 |
| adapter-outbound `ConversationGatewayFactory` | app이 `AgentClientFactory`를 직접 씀 | 삭제 |
| adapter-outbound `NoOpConversationGateway` + `ConversationGatewayFactoryTest` | 인자 없는 `create()`는 테스트에서만 쓰임. app은 null로 대화를 끔 | 삭제 |
| `CodexTopicAssignmentGenerator`, `CodexSourceReferenceInterpreter` 이름과 "Codex" 주석 | 더 이상 Codex 전용이 아님 | `ModelTopicAssignmentGenerator`, `ModelSourceReferenceInterpreter`로 이름 변경(테스트 포함) |
| `ApplicationServices`의 "local Codex runtime" 주석, `application/usecase/memory/analysis/README.md`의 Codex transport 설명 | 사실과 달라짐 | backend 중립 문구로 수정 |
| `integration-llm/.../codex/transport/README.md`의 Completion 절 | `CompletionClient` 위치 설명이 옛 구조 | 경로 수정 |

## 유지하는 것

- DB 물리명 `slack_codex_sessions`, `codex_thread_id` 등: 기존 데이터 호환을 위해 이름을 유지한다.
  Claude 세션 UUID도 같은 컬럼에 저장된다. backend를 바꾼 직후 10분 안에 이어지는 활성 대화는 다른
  backend의 id를 resume하지 못해 그 요청 하나가 실패하고, 다음 요청부터 새 세션이 열린다. 재시작 후
  10분이 지나 전환하면 영향이 없다.
- `ApplicationPackageArchitectureTest`의 `codex` 금지어: output port 기술 이름 검사이므로 유지한다.
  필요하면 `claude`를 추가한다.
- `.codex/hooks.json`: 개발 도구 설정이며 런타임과 무관하다.

## 완료 조건

- adapter-outbound와 app에 `com.homeassistant.codex` import와 `integration-codex` 의존이 없다.
- `AGENT_BACKEND`만 바꿔 두 backend로 지식 주입과 대화가 동작한다.
- 기본값(`codex`)에서 기존 동작과 로그 범주가 유지된다.
- `./gradlew test`가 통과한다.

## 제외 범위

- 작업별로 다른 backend 고르기(분석은 codex, 대화는 claude 등)
- 모델·effort를 env로 노출하기
- DB 컬럼 이름 변경 마이그레이션

## 구현 결과 (2026-10-19)

- `AGENT_BACKEND`와 `AGENT_TIMEOUT_SECONDS`를 추가하고 `CODEX_TIMEOUT_SECONDS`를 제거했다.
  알 수 없는 backend 값은 저장소를 열기 전에 시작을 실패시킨다.
- app이 completion client 하나를 만들어 Memory 분석·Topic 배정·이미지 해석 Factory에 넘기고, 대화는
  `AgentClientFactory.conversation`을 그대로 쓴다.
- adapter-outbound는 `integration-llm`의 `CompletionClient`/`CompletionImage`만 의존한다.
  `CodexTopicAssignmentGenerator`·`CodexSourceReferenceInterpreter`를 `Model*`로 이름을 바꿨다.
- `integration-codex` 모듈, adapter-outbound의 `CodexConversationGateway`·`ConversationGatewayFactory`·
  `NoOpConversationGateway`와 그 테스트를 제거했다.
- analysis README, Codex transport README, 배포 문서, core 경계 강화 문서의 Codex 전용 서술을 고쳤다.
- 검증: `./gradlew test` 통과(domain 21, application 45, adapter-inbound 25, adapter-outbound 84, app 4,
  integration-llm 46). 실제 서버 기동과 Claude CLI 실호출은 아직 하지 않았다.
- 이후 기본 backend를 `claude`로 바꿨다. 계획의 "기본값 `codex`로 현재 동작 유지"는 이 결정으로 대체한다.
- 이후 대화 계약을 integration-llm의 `ConversationClient`로 분리했다. integration-llm은 application을 의존하지 않고, adapter-outbound의 `AgentConversationGateway`가 application `ConversationGateway`로 연결한다. 의존 방향은 `app -> adapter-outbound -> integration-llm`이고 application port를 아는 모듈은 adapter-outbound뿐이다.
