# 최소 가족 Task 서비스

- 상태: TODO
- 우선순위: Feature P0
- 선행 작업: 없음

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
- Task 할당과 완료 이벤트를 Notification이 사용할 수 있게 한다.

## 최소 모델

- Task ID
- 짧은 내용
- 생성자 application user ID
- 담당자 application user ID
- `OPEN` 또는 `COMPLETED` 상태
- 생성 시각과 선택적인 완료 시각

## 구현 순서

1. Task와 상태 전이 규칙을 domain에 정의한다. 완료된 Task를 다시 완료하는 요청은 idempotent하게
   처리한다.
2. 생성, 담당자별 미완료 조회, 완료 처리를 application input port와 use case로 제공한다.
3. Task 저장과 상태 변경을 persistence output port로 분리하고 SQLite에 보존한다.
4. Task가 commit된 뒤 할당 또는 완료 Notification을 요청한다. 알림 실패가 저장된 Task를 되돌리지
   않도록 전달 경계를 분리한다.
5. Slack에서 자연스러운 최소 진입점과 완료 동작을 제공하되 Slack 상태를 application/domain에
   넣지 않는다.
6. 정상 할당, 다른 가족 담당 Task, 중복 완료, 알림 실패 후 Task 보존을 회귀 테스트로 고정한다.
7. 새 leaf use case package의 README에 정상 흐름과 실패 branch를 Mermaid sequence diagram으로
   기록한다.

## 완료 조건

- 등록된 가족에게 Task를 할당하고 담당자의 미완료 Task를 조회할 수 있다.
- 담당자가 Task를 완료하면 이후 조회에서 완료 상태가 확인된다.
- 중복 요청이 Task나 완료 이력을 중복 생성하지 않는다.
- 전달 실패가 Task 생성·완료 transaction을 롤백하지 않는다.
- Task에는 우선순위, 선행 관계, 세부 단계 같은 프로젝트 관리 개념이 없다.
- 전체 테스트가 통과한다.

## 제외 범위

- 마감일, 우선순위, dependency, subtask, recurrence, estimate
- 보드, sprint, backlog, progress percentage
- 댓글 thread와 파일 첨부
- 범용 workflow 또는 규칙 엔진
