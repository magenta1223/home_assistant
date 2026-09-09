# 가족 Notification 서비스

- 상태: HOLD — proactive delivery 채널 결정 대기
- 우선순위: Feature P1
- 선행 작업: [최소 가족 Task 서비스](../p0/minimal-family-task-service.md)의 실제 운영 결과

## 현재 결정

Slack은 frozen legacy adapter이고 현재 HTTP 화면은 사용자가 직접 여는 pull 채널이다. 확정되지 않은
delivery 채널을 전제로 worker, 승인 UI와 Memory 추론을 먼저 만들지 않는다.

재개 조건은 다음 두 가지다.

1. 가족이 실제로 놓치는 Task 또는 명시적 알림 사례가 반복된다.
2. HTTP push, 모바일 push 등 인증된 proactive delivery 채널 하나를 선택한다.

## 문제

Task나 Memory에 시점이 기록되어도 사용자가 다시 화면을 열지 않으면 놓칠 수 있다. 외부 전달은
재시작·재시도 중 중복될 수 있고, 잘못된 수신자 선택은 권한 누출로 이어진다.

## 첫 구현 범위

- 사용자가 명시적으로 요청했거나 Task 상태가 결정론적으로 만든 알림만 저장한다.
- application `UserId`, 메시지 snapshot, 전달 시각, 상태와 idempotency key를 보존한다.
- due 알림을 lease로 claim하고 성공, 제한적 재시도와 최종 실패를 기록한다.
- delivery adapter는 application 사용자를 채널 identity로 변환하고 전송 결과만 반환한다.
- producer transaction은 delivery 실패 때문에 롤백하지 않는다.

Memory에서 모델이 날짜·수신자를 추론해 후보를 만들고 별도 승인 workflow를 운영하는 기능은 실제
명시적 알림이 안정적으로 사용된 뒤에만 검토한다.

## 구현 순서

1. 실제 사용 사례와 delivery 채널 하나를 결정하고 인증·권한·실패 계약을 기록한다.
2. 확정 Notification의 최소 domain 상태와 application input/output port를 정의한다.
3. SQLite 저장, due claim, lease recovery와 bounded retry를 구현한다.
4. 선택한 채널 adapter 하나를 연결한다.
5. Task의 할당·완료 알림 중 실제로 필요한 이벤트만 producer로 연결한다.
6. 중복 요청, 재시작, 만료, 영구 실패와 다른 사용자 전달 거부를 회귀 테스트로 고정한다.

## 완료 조건

- 지정 시각과 수신자 `UserId`가 명시된 알림만 생성된다.
- 재시작과 재시도에도 Notification record와 claim이 중복되지 않는다.
- 외부 전달의 at-least-once 한계와 중복 가능성이 문서화된다.
- 권한 밖 사용자에게 메시지나 resource reference가 전달되지 않는다.
- 전달 실패가 Task나 다른 producer의 저장을 되돌리지 않는다.
- 전체 테스트와 선택한 delivery 채널의 운영 smoke test가 통과한다.

## 제외 범위

- Slack 기능 추가
- Memory 기반 자동 후보 발견과 승인 workflow
- 반복·위치·센서·임의 조건 trigger
- 범용 automation DSL, escalation과 복잡한 사용자 선호도

## 현재 상태 (2026-09-08)

구현하지 않았다. 먼저 최소 Task 서비스를 HTTP에서 사용해 실제 proactive delivery 필요를 확인한다.
채널이 결정되기 전에는 domain, worker 또는 adapter를 만들지 않는다.
