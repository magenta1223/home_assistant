# 최소 가족 Task 서비스

- 상태: TODO
- 우선순위: Feature P0
- 선행 작업: 없음

> 2026-09-08 범위 정리: frozen Slack과 보류 중인 Notification Service에 연결하지 않는다. 기존
> Bearer 인증 HTTP 경계와 작은 웹 화면으로 먼저 제공하고 실제 사용 결과로 후속 알림 필요를 판단한다.

## 문제

가족에게 부탁한 일이 대화 속에서 사라진다. 기존 Memory는 사실을 보존하고 질문에 답할 수 있지만,
특정 가족이 해야 할 일이 아직 남아 있는지와 완료됐는지는 명시적인 상태로 유지하지 않는다.

집안일을 Jira처럼 관리하는 것은 제품 방향이 아니다. Task 관리를 위해 다시 세부 계획을 입력하거나
지속적으로 보드를 정리하게 만들어서는 안 된다.

## 목표

- 등록된 가족 구성원에게 하나의 Task를 할당한다.
- Task가 미완료인지 완료됐는지만 확인하고 변경한다.
- 누가 언제 생성하고 완료했는지 최소 이력을 보존한다.
- Slack 같은 채널과 분리된 application use case로 제공한다.
- 인증된 HTTP 사용자에게 생성, 내 미완료 조회와 완료 기능을 제공한다.

## 최소 모델

- Task ID
- 짧은 내용
- 생성자 application user ID
- 담당자 application user ID
- `OPEN` 또는 `COMPLETED` 상태
- 생성 시각과 선택적인 완료 시각

## 접근 규칙

- creator와 assignee는 등록된 application 사용자여야 한다.
- 인증 principal이 creator가 되며 request body로 creator를 지정할 수 없다.
- assignee는 자신의 Task를 조회하고 완료할 수 있다.
- creator는 자신이 만든 Task의 상태를 조회할 수 있지만 다른 사용자를 대신해 완료하지 않는다.
- 생성과 완료 요청은 client request ID로 중복 처리를 막는다.

## 구현 순서

1. Task와 상태 전이 규칙을 domain에 정의한다. 완료된 Task를 다시 완료하는 요청은 idempotent하게
   처리한다.
2. 생성, 담당자별 미완료 조회, 완료 처리를 application input port와 use case로 제공한다.
3. Task 저장과 상태 변경을 persistence output port로 분리하고 SQLite에 보존한다.
4. 기존 Bearer principal 아래에 Task 생성, 내 미완료 조회, 내가 만든 Task 조회와 완료 HTTP route를
   추가한다. `UserId`는 path/body가 아니라 인증 principal에서 가져온다.
5. `/tasks`에 내용·담당자 입력, 내 미완료 목록과 완료 동작만 있는 작은 화면을 제공한다.
6. 정상 할당, 미등록 담당자, 다른 사용자 조회·완료 거부와 요청 dedup을 회귀 테스트로 고정한다.
7. 새 leaf use case package의 README에 정상 흐름과 실패 branch를 Mermaid sequence diagram으로
   기록한다.

## 완료 조건

- 등록된 가족에게 Task를 할당하고 담당자의 미완료 Task를 조회할 수 있다.
- 담당자가 Task를 완료하면 이후 조회에서 완료 상태가 확인된다.
- 중복 요청이 Task나 완료 이력을 중복 생성하지 않는다.
- 인증 사용자가 다른 사용자의 Task를 조회하거나 완료할 수 없다.
- Task 생성자 identity를 request body로 위조할 수 없다.
- Task에는 우선순위, 선행 관계, 세부 단계 같은 프로젝트 관리 개념이 없다.
- 전체 테스트가 통과한다.

## 제외 범위

- 마감일, 우선순위, dependency, subtask, recurrence, estimate
- 보드, sprint, backlog, progress percentage
- 댓글 thread와 파일 첨부
- 범용 workflow 또는 규칙 엔진
- Slack command, modal 또는 DM
- Notification 생성과 proactive delivery

## 현재 상태 (2026-09-08)

미구현이다. 현재 저장소에는 Task domain, application port, persistence와 HTTP route가 없다. 기존
HTTP 사용자 인증과 등록 사용자 조회를 재사용할 수 있어 새 인증 체계나 채널 abstraction은 필요하지
않다.
