# Slack 운영 채널 아키텍처

- 상태: CANCELED
- 우선순위: Feature P0
- 선행 작업: 없음
- 종료일: 2026-08-20

## 목표

Slack을 단일 슬래시 명령 모음이 아닌 웹 UI와 동등한 운영 채널로 확장한다. 채널별 표현은 분리하고,
지식 주입을 포함한 운영 기능은 공통 workflow 위에서 추가한다.

## 원칙

- Slack, 로컬 웹 UI 등 **채널**은 입력·표시·상호작용 어댑터이며 업무 규칙을 소유하지 않는다.
- 지식 주입, 검색, 배치 같은 **기능**은 채널과 독립된 application input port와 workflow를 가진다.
- 기능 레지스트리가 기능 ID, 지원 채널, 권한, 진입점과 상태를 선언한다. 채널은 레지스트리에 없는 기능을 노출하지 않는다.
- modal, 확인, 진행 상태, 재시도, 결과 전달 같은 상호작용은 공통 workflow 계약으로 다룬다.
- 호출자 identity, 권한 판정, 요청·결과·실패 감사는 공통 경계에서 기록한다. 채널이 전달한 user ID나 payload를 신뢰하지 않는다.
- 새 기능은 작은 플러그인 계약(기능 선언, 입력 검증, workflow 단계, 권한 요구, 감사 이벤트, 채널 renderer)으로 추가한다. 임의 명령 문자열을 실행하는 확장점은 만들지 않는다.

## 구현 계획

1. 기존 Slack DM의 사용자 조회, 등록 상태 전이, 최초 질문 보관·재개, memory 답변 routing을 기술 중립적인 application input port로 이동한다. **완료**
2. Slack adapter를 인증된 이벤트 변환, 즉시 ack, modal/block 렌더링과 결과 전달로 제한한다. **완료**
3. 운영 기능·채널·workflow·감사 이벤트의 최소 domain/application 모델과 책임 경계를 ADR로 확정한다.
4. 기능 레지스트리와 채널별 capability 조회 계약을 추가한다.
5. 공통 interaction/workflow 상태 모델을 정의한다. 재시도·중복 요청·세션 만료·사용자 취소의 소유자를 명시한다.
6. 모든 운영 요청에 인증된 actor, 권한 결정, 상관관계 ID를 연결하고 감사 저장·조회 보존 정책을 정한다.
7. Slack adapter가 등록된 기능만 노출·렌더링하도록 하고, 기존 DM 답변 흐름과 충돌하지 않게 전환 계획을 만든다.
8. 첫 구현체인 Slack 지식 주입은 Feature P1에서 구현한다.

## 구현된 기반

- `MemoryAnswerWorkflow`가 외부 conversation identity를 받아 사용자를 resolve하고 등록 필요 여부와 memory 답변을 결정한다.
- 미등록 사용자의 최초 질문은 application output port 뒤의 SQLite 저장소에 보존되며 등록 완료 후 같은 application workflow에서 재개한다.
- 표시 이름 정규화와 길이 제한은 domain invariant이며 application이 검증 결과를 채널에 제공한다.
- Slack modal의 channel metadata는 Slack payload에서 재사용하지 않고 application에 보관된 최초 질문의 reply key에서 생성한다.

## 완료 조건

- 기능 추가가 Slack listener·명령 분기 수정만으로 이루어지지 않고 명시적 기능 계약을 따른다.
- 채널 adapter와 기능 workflow의 의존 방향이 모듈 경계에 맞는다.
- 권한 거부, 입력 검증 실패, 취소, 재시도, 완료가 추적 가능한 감사 이벤트를 남긴다.
- Slack 지식 주입이 이 계약만으로 구현 가능한 수준의 API와 상태 전이가 정해진다.

## 제외 범위

- 임의의 범용 플러그인 마켓플레이스
- Slack 외 채널의 실제 구현
- 기존 canonical memory 권한 모델 변경

## 종료 내용

계획의 1~2단계인 사용자 등록·memory 답변 application 경계 분리는 완료했고
`slack-memory-answer-application-boundary.md`에 별도 완료 이력으로 남겼다. 나머지 운영 기능 registry,
공통 interaction 상태, 감사 저장소와 Slack 지식 주입 기반은 구현하지 않는다.

범용 운영 기능 registry, 공통 interaction 상태와 감사 저장소를 한 번에 도입하는 계획은 취소한다.
이후 명시적으로 승인된 지식 주입 범위에서는 작은 `SlackSlashCommand` 계약과 중복을 거부하는
registry만 구현하고, 업무 규칙은 `KnowledgeInjectionWorkflow`에 유지했다.

## 검증

- Slack inbound에는 등록·DM memory 답변과 `/knowedge` 지식 주입 경로가 있다.
- 로컬 지식 주입은 `/knowledge`와 `/api/knowledge/import/analyze`에 그대로 유지되어 있다.
- 2026-08-20: `:application:test`, `:adapter-inbound:test`, `:adapter-outbound:test` 통과

## 남은 제약

- 후속 command는 실제 반복이 확인된 범위만 `SlackSlashCommand` 구현으로 추가한다.
