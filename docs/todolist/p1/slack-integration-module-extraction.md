# Slack integration 모듈 분리

- 상태: TODO
- 우선순위: P1
- 선행 작업: 없음
- 관련 작업: [core-technology-boundary-hardening.md](../p2/core-technology-boundary-hardening.md)

## 문제

`adapter-inbound/slack`의 7개 파일, 약 1,200줄에는 서로 다른 수준의 책임이 함께 있다.

- Slack Java SDK, Web API와 Socket Mode lifecycle
- message, modal, file download 같은 Slack 고유 통신
- Bolt listener와 interaction routing
- `MemoryAnswerWorkflow`, `KnowledgeInjectionWorkflow`를 호출하는 application input adapter
- application result를 Slack message/modal로 변환하는 기능별 presentation

특히 `SlackClient`는 application input port를 구현하지 않으면서 memory answer와 knowledge
injection adapter가 함께 사용하는 외부 시스템 client다. Slack은 event 수신과 응답 전송이 하나의
연결에 모인 bidirectional integration이므로 이를 단순 inbound helper로 취급하면 기술 lifecycle과
기능 adapter 경계가 보이지 않는다.

## 목표

- Slack SDK, Web API, Socket Mode와 Slack protocol 모델을 독립 `integration-slack` 모듈로
  분리한다.
- `adapter-inbound`에는 Slack 요청을 application input port 호출로 변환하고 결과를 Slack 표현으로
  매핑하는 기능별 adapter만 남긴다.
- `integration-slack`가 application/domain을 의존하지 않게 한다.
- 기존 DM 등록·답변, `/knowledge` modal, file download와 Socket Mode 동작을 유지한다.
- Slack을 provider-neutral message bus로 일반화하지 않는다.

## 범위

### `integration-slack` 후보

- Slack Web API client와 Slack 고유 요청·결과·실패 모델
- Socket Mode 생성, 시작, 종료와 연결 lifecycle
- Bolt listener 등록에 필요한 Slack event/interaction envelope
- message posting, modal open/update와 authenticated file download
- Slack SDK configuration과 transport-level validation

### `adapter-inbound`에 유지

- Slack identity를 application `ConversationIdentity`로 변환하는 코드
- `MemoryAnswerWorkflow`, `KnowledgeInjectionWorkflow` 호출
- 등록, memory answer와 knowledge injection의 기능별 분기
- application result/exception을 사용자 message와 modal로 렌더링하는 코드
- Kakao/plain-text source parser 호출과 domain draft 생성

## 구현 순서

1. `SlackClient`, `SlackRuntime`, listeners와 두 기능 adapter의 실제 호출 관계를 테스트로 고정한다.
2. Slack SDK type과 application/domain type을 동시에 사용하는 지점을 목록화하고 양쪽 mapping
   경계로 지정한다.
3. application/domain 의존성이 없는 `integration-slack` Gradle 모듈을 만든다.
4. Web API, file download와 Socket Mode lifecycle을 먼저 이동한다.
5. Slack SDK callback을 integration 고유 event 모델로 변환하고, 기능 adapter가 이를 받아
   application request를 만들게 한다. SDK type을 그대로 재노출해 모듈 분리를 무의미하게 만들지
   않는다.
6. `SlackIdentity`의 legacy application-user mapping과 환경 설정 책임은 adapter/composition
   경계에 남긴다.
7. app composition이 integration runtime과 기능별 Slack adapter의 lifecycle을 한 곳에서
   조립하게 한다.
8. Slack SDK 의존성을 `adapter-inbound`에서 제거할 수 있는지 확인한다. 기능 rendering 때문에
   직접 의존성이 꼭 남는다면 무리한 wrapper를 만들지 말고 모듈 경계를 재평가한다.
9. DM, 등록 modal, `/knowledge`, file import, 재시도와 종료 회귀 테스트를 통과시킨다.

## 완료 조건

- `integration-slack`가 application/domain/adapter 모듈을 의존하지 않는다.
- Slack SDK, Web API client와 Socket Mode lifecycle이 integration 모듈에만 존재한다.
- `adapter-inbound`의 Slack 코드는 application input port 호출과 기능별 request/result mapping에
  집중한다.
- Slack SDK DTO가 application/domain으로 유출되지 않는다.
- message delivery와 modal 실패 의미가 기존 사용자 흐름에서 유지된다.
- DM 등록·답변, `/knowledge`, file download와 shutdown 테스트가 통과한다.
- 전체 `gradlew test`가 통과한다.

## 제외 범위

- Slack을 범용 notification/chat provider abstraction으로 일반화
- application use case나 Slack UX 변경
- 새로운 Slack 기능 또는 scope 추가
- P2의 application notification port 결정을 이 작업에서 선행 구현

## 현재 상태 (2026-09-06)

미구현이다. `integration-slack` 모듈은 없고 Slack SDK, Web API와 Socket Mode lifecycle은 계속
`adapter-inbound/slack`에 있다. 현재 기능은 정상 동작하므로 P1 구조 개선 작업으로 유지한다.
