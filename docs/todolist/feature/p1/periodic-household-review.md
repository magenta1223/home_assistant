# 정기 가정 운영 Review

- 상태: TODO
- 우선순위: Feature P1
- 선행 작업: 최소 가족 Task 서비스, 가족 Notification 서비스

## 문제

개별 사실과 Task를 질문해서 확인할 수 있어도 가족이 무엇을 물어봐야 하는지 놓칠 수 있다.
Memory, 미완료 Task와 최근 Notification 이력을 주기적으로 종합해 중요한 변화와 다가오는 관심사를
먼저 알려주는 기능이 필요하다.

Review를 또 하나의 관리 문서나 회의 절차로 만들어서는 안 된다. 가족의 추가 입력 없이 기존 기록을
읽어 짧고 유용한 브리핑을 전달하는 것이 목적이다.

## 목표

- 정해진 주기에 사용자별로 열람 가능한 Memory, 미완료 Task와 Notification 이력을 종합한다.
- 최근 결정, 미완료 작업, 반복 문제와 곧 확인할 일을 짧게 브리핑한다.
- 각 내용의 근거 Memory 또는 Task를 추적할 수 있게 한다.
- 공유할 내용이 없으면 불필요한 알림을 보내지 않는다.
- Review가 Task를 자동 생성하거나 Memory를 임의로 수정하지 않는다.

## 구현 순서

1. Review 대상 기간, 수신자와 마지막 성공 실행을 나타내는 최소 실행 계약을 정의한다.
2. 사용자에게 보이는 Memory, 미완료 Task, 기간 내 Notification 결과를 읽는 context source를
   application 경계에 둔다.
3. Codex가 근거 밖의 사실이나 새로운 의무를 만들지 않도록 evidence-grounded Review prompt와
   결과 검증을 구현한다.
4. 고정된 초기 주기로 Review를 실행하고 결과가 있을 때 Notification 서비스를 통해 전달한다.
5. 같은 기간과 수신자의 중복 실행·중복 전달을 idempotency key로 막는다.
6. 권한 분리, 빈 Review 억제, 실패 후 재실행과 근거 연결을 회귀 테스트로 고정한다.
7. 새 leaf use case package의 README에 정상 흐름과 실패 branch를 Mermaid sequence diagram으로
   기록한다.

## 완료 조건

- 설정된 주기에 가족별 Review가 자동 실행된다.
- Review는 해당 사용자가 볼 수 있는 Memory와 Task만 사용한다.
- 중요한 내용이 있을 때만 Notification으로 전달된다.
- 각 항목이 기존 Memory 또는 Task 근거와 연결되고 모델이 만든 새 사실로 저장되지 않는다.
- 서버 재시작이나 재시도에도 같은 기간 Review가 중복 전달되지 않는다.
- 전체 테스트가 통과한다.

## 제외 범위

- 대시보드, 점수, KPI와 가정 생산성 평가
- Review를 위한 별도 수동 회의·승인 workflow
- 자동 Task 생성 또는 자동 의사결정
- 장기 통계·예측 모델
- 사용자별 복잡한 schedule 편집 UI
