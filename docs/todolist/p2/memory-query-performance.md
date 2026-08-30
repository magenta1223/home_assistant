# Memory DB 조회 최적화

- 상태: 사용자 학습 과제로 보류
- 우선순위: P2
- 선행 작업: 실제 Memory 수와 query 시간 계측

## 진행 원칙

현재 사용을 막는 성능 문제는 아니며, 사용자가 서비스 구현과 운영을 공부하면서 직접 점진적으로
개선할 학습 과제다. 명시적인 요청 전에는 자동으로 구현 우선순위를 올리거나 대신 완료하지 않는다.

진행할 때는 실제 query 수와 시간을 먼저 측정하고, SQL visibility filtering과 evidence batch
조회처럼 한 가지 변경씩 적용해 전후 결과를 비교한다. 최종 구조만 만드는 것보다 query plan,
transaction 경계, N+1 발생 원인과 운영 지표를 이해할 수 있는 과정을 함께 남긴다.

## 문제

모든 memory를 조회한 뒤 애플리케이션에서 visibility를 필터링한다. 각 memory의 evidence를 별도 query로 가져와 N+1 query가 발생한다. N-depth 렌더링은 Codex 입력만 줄이고 DB 조회량은 줄이지 않는다.

## 계획

1. visibility 조건을 SQL query에 적용한다.
2. 필요한 memory ID들의 evidence를 한 번에 조회한다.
3. 배치 후보용 root/N-depth 조회와 일반 전체 조회를 분리한다.
4. 실제 memory 수와 query 시간을 계측한 뒤 최적화 전후를 비교한다.
5. JSON `childrenIds`가 병목이나 무결성 문제를 만들면 nullable `parent_id` 또는 관계 테이블로 migration한다.
6. migration 전에는 speculative repository abstraction을 추가하지 않는다.

## 완료 조건

- visible memory 조회 query 수가 memory 개수에 비례해 증가하지 않는다.
- 배치 후보 조회가 전체 memory를 domain object로 복원하지 않는다.
