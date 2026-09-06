# Codex integration 모듈 분리

- 상태: 계획
- 우선순위: P0
- 작업 소유자: 사용자 학습 과제 — 사용자의 명시적 요청 없이 대신 구현하지 않음
- 선행 작업: 없음

## 문제

현재 `adapter-outbound`의 `codex/`에는 서로 다른 수준의 코드가 섞여 있다.

- `CodexCliClient`, process executor, app-server transport와 JSONL parser처럼 Codex CLI protocol과
  process lifecycle만 다루는 저수준 integration
- `ConversationTurnClient` 같은 application output port를 구현하는 outbound adapter
- memory analysis, memory placement와 source reference 해석 adapter가 공유하는 structured completion
  client

이 때문에 `adapter.outbound.codex`가 독립된 application outbound port처럼 보이며,
`adapter-outbound`가 가진 기능별 port 구현 구조도 흐려진다. 반대로 Codex 통신 코드는 단순 support
utility라 부르기에는 process 실행, structured output, image 전달, 장기 app-server 연결, protocol
parsing과 lifecycle을 함께 소유할 만큼 크다.

## 목표

- application/domain을 모르는 독립 `integration-codex` 모듈에 Codex CLI 통신 책임을 모은다.
- `adapter-outbound`에는 application output port를 구현하고 Codex integration을 기능 의미로
  변환하는 adapter만 남긴다.
- memory analysis, placement, reference interpretation과 conversation이 저수준 Codex 통신을
  재사용하되 서로의 기능 package에 의존하지 않게 한다.
- 현재 prompt, schema, image, timeout와 conversation 동작을 바꾸지 않는 구조 분리로 제한한다.
- 새 모듈의 public API가 application port를 복제하거나 범용 LLM abstraction으로 커지지 않게 한다.

## 목표 구조

```text
integration-codex/
  cli/             # executable 확인과 process 실행
  completion/      # structured completion와 image 입력
  appserver/       # 장기 process transport와 Codex protocol

adapter-outbound/
  memoryanalysis/codex/   # MemoryExtractor, MemoryPlacementExtractor 구현
  reference/codex/        # SourceReferenceInterpreter 구현
  memoryconversation/codex/ # ConversationTurnClient 구현
```

의존 방향은 다음으로 고정한다.

```text
app -> adapter-outbound -> integration-codex
                       -> application -> domain

integration-codex -X-> application/domain
```

`integration-codex`의 API는 Codex라는 외부 시스템의 기능과 protocol을 표현한다.
`adapter-outbound`가 이를 application의 `MemoryExtractor`, `SourceReferenceInterpreter`,
`ConversationTurnClient` 계약으로 변환한다.

## 범위

### `integration-codex`로 이동

- Codex executable 기본 경로와 availability probe
- process 실행, stdin/stdout/stderr, timeout와 임시 작업 directory 관리
- structured completion 요청과 output schema/result 파일 처리
- Codex image 입력 전송 모델
- app-server process transport와 요청/응답 protocol
- JSONL event parsing 중 application 의미를 모르는 부분
- Codex 고유 model/reasoning/CLI option 값 객체

### `adapter-outbound`에 유지

- `MemoryExtractor`, `MemoryPlacementExtractor` 구현과 prompt/output mapping
- `SourceReferenceInterpreter` 구현과 PDF/image 전처리
- `ConversationTurnClient` 구현과 `ConversationTurnResult` mapping
- application/domain 모델을 사용하는 factory와 조립 코드
- 기능별 오류를 application output port 의미로 변환하는 경계

### 제외

- Codex를 범용 `LlmClient` 또는 provider-neutral abstraction으로 일반화
- application에 prompt, JSON schema나 Codex thread protocol 노출
- prompt 내용, 모델 선택 정책 또는 reasoning effort 변경
- Codex CLI를 hosted API나 다른 provider로 교체
- memory analysis/reference/conversation 동작 변경
- ONNX embedding 전환과 결합

## 구현 순서

### 1. 현재 dependency와 행위 기준선 고정

1. `adapter.outbound.codex`의 각 type을 저수준 integration과 application adapter로 분류한다.
2. memory analysis, placement, reference와 conversation의 호출 관계를 기록한다.
3. 기존 테스트가 보장하는 command 인자, stdin, schema/output 파일, image, timeout, app-server
   재시작과 conversation 결과 mapping을 기준선으로 고정한다.
4. application/domain import가 필요한 type은 새 integration 모듈로 이동하지 않는다는 규칙을
   적용한다.

### 2. 빈 `integration-codex` 모듈 생성

1. `settings.gradle.kts`에 모듈을 등록한다.
2. Kotlin/JVM, serialization, coroutine과 logging 중 실제 저수준 코드가 사용하는 최소 의존성만
   선언한다.
3. `application`, `domain`, `adapter-outbound`, `configuration`에는 의존하지 않게 한다.
4. 환경 변수 해석과 프로젝트 기본값은 모듈 밖 composition/configuration 경계에 남긴다.

### 3. structured completion integration 이동

1. `CompletionClient`, `CodexCliClient`, `CodexImage`, process executor와 결과 type을 새 모듈로
   이동한다.
2. `MEMORY_GENERATION_MODEL`처럼 특정 기능 이름을 가진 기본값은 저수준 client에서 제거하거나
   Codex 요청 configuration으로 전달한다.
3. memory analysis, placement와 reference adapter가 새 모듈의 completion API를 주입받게 한다.
4. 임시 파일 정리, command 구성, timeout와 image 전달 기존 테스트를 새 모듈로 이동한다.

### 4. app-server integration과 conversation adapter 분리

1. process transport, request ID 관리, protocol message와 Codex thread/turn event 처리를 application
   모델과 분리할 수 있는 경계까지 새 모듈로 이동한다.
2. Codex protocol 결과를 `ConversationTurnResult`로 바꾸는 구현은 `adapter-outbound`에 둔다.
3. `ConversationClient : ConversationTurnClient`처럼 두 경계를 합친 interface는 기능 adapter와
   integration client로 나눈다.
4. availability, start/close/restart lifecycle은 소유자가 하나만 되도록 정한다.
5. thread ID 검증 중 Codex protocol 규칙은 integration에, application session 정책은 adapter 또는
   application에 남긴다.

### 5. package와 composition 정리

1. `adapter-outbound`의 Codex 구현을 각 application output port의 기능 package 아래로 이동한다.
2. `ApplicationServicesFactory`는 기능별 outbound factory만 알고 저수준 transport를 직접 조작하지
   않게 한다.
3. 같은 Codex configuration이나 process resource를 공유해야 하면 composition root에서 명시적으로
   생성·주입한다. 공유 필요가 없으면 억지 singleton은 만들지 않는다.
4. 비게 된 `adapter.outbound.codex` package와 잘못된 import를 제거한다.
5. `AGENTS.md`의 module architecture에 `integration-codex` 책임과 의존 방향을 추가한다.

### 6. 회귀 검증

1. `integration-codex` 단위 테스트에서 process command, timeout, output parsing, image 파일,
   app-server lifecycle과 protocol 오류를 검증한다.
2. `adapter-outbound` 테스트에서 Codex integration fake를 주입해 각 application port mapping을
   검증한다.
3. module dependency report 또는 build 설정 검사로 `integration-codex`가 application/domain을
   참조하지 않는지 확인한다.
4. memory analysis, source reference interpretation과 Slack memory conversation의 기존 회귀 테스트를
   모두 실행한다.
5. 전체 `gradlew test`를 통과시킨다.

## 완료 조건

- `integration-codex`가 별도 Gradle 모듈로 존재하고 application/domain/adapter 모듈을 의존하지
  않는다.
- Codex process, structured completion, image, app-server transport와 protocol parsing이 새 모듈에
  위치한다.
- `adapter-outbound`에는 application output port 구현과 application/domain mapping만 남는다.
- `adapter.outbound.codex`라는 모호한 최상위 package가 남지 않는다.
- application에는 Codex CLI command, prompt transport, schema 파일이나 app-server protocol type이
  노출되지 않는다.
- memory analysis, placement, reference interpretation과 conversation의 기존 동작과 failure mapping이
  유지된다.
- 새 모듈 API가 단일 provider만 존재하는 상황에서 불필요한 범용 LLM abstraction을 만들지 않는다.
- 관련 단위·통합 테스트와 전체 `gradlew test`가 통과한다.

## 구현 결과

아직 구현하지 않았다. 이 문서는 사용자가 직접 수행할 학습 과제이며, 사용자의 별도 요청 전에는
구현하지 않는다.
