# Topic·Group 병합과 kNN 연결

- 상태: TODO — 구현 방식 탐색 중
- 우선순위: P1
- 선행 작업: Memory → Topic → Group 정리 (2026-10-19)

## 문제

xMemory는 구성원이 적거나 고립된 묶음을 이웃과 병합하고, 묶음·component 사이에 k=10 이웃 링크를 유지한다.
현재 구현에는 둘 다 없다. 작은 Topic·Group이 계속 늘어나고 TopicRelation은 자동으로 채워지지 않는다.

## 목표

논문 규칙을 그대로 옮기기 전에 더 나은 병합 기준과 연결 방식을 찾아 비교한 뒤 구현 방식을 정한다.

## 참고

- 저장 계약: Group 저장은 N→1 병합을 이미 수용한다. `TopicMaintenanceStore`는 Topic 삭제·소속 교체가 없으므로 Topic 병합 시 계약 변경이 필요하다.
- Topic 자체 분할도 아직 없다. LM 배정으로 Topic이 커질 수 있다.
- [xMemory v4](https://arxiv.org/html/2602.02007v4) Appendix A.2 merge trigger, kNN k=10.
