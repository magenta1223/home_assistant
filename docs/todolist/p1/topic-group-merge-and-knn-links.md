# Topic·Group 병합과 kNN 연결

- 상태: TODO — Group 병합 완료(2026-10-21), Group·Topic kNN 이웃은 검색 시 계산(2026-10-22), Topic 분할·병합 보류
- 우선순위: P1
- 선행 작업: Memory → Topic → Group 정리 (2026-10-19)

## 문제

xMemory는 구성원이 적거나 고립된 묶음을 이웃과 병합하고, 묶음·component 사이에 k=10 이웃 링크를 유지한다.
Group 병합은 논문 규칙대로 구현했다([usecase/group README](../../../application/src/main/kotlin/com/homeassistant/application/usecase/group/README.md#group-병합)).
이웃 링크는 저장하지 않고 검색할 때 계층 전체와 cosine으로 계산한다([Topic 검색](../../../application/src/main/kotlin/com/homeassistant/application/usecase/topic/README.md#topic-검색)).
Topic 사이 연결은 저장하지 않는다(TopicRelation은 2026-10-20 제거).

Topic 분할·병합은 보류한다. Memory가 여러 Topic에 속하므로 Topic 분할 점수에서 이웃 묶음 항을 빼야 하는데,
그 점수는 크기가 고른 분할이면 거의 항상 올라 분할 여부를 정하지 못한다. 분할 없는 Topic 병합은 의미가 적다.

## 목표

논문 규칙을 그대로 옮기기 전에 더 나은 병합 기준과 연결 방식을 찾아 비교한 뒤 구현 방식을 정한다.

## 참고

- 저장 계약: Group 저장은 N→1 병합을 이미 수용한다. `TopicMaintenanceStore`는 Topic 삭제·소속 교체가 없으므로 Topic 병합 시 계약 변경이 필요하다.
- Topic 자체 분할도 아직 없다. 온라인 cosine 배정이라 먼저 들어온 Memory가 Topic 중심을 정하고 Topic이 커질 수 있다.
- [xMemory v4](https://arxiv.org/html/2602.02007v4) Appendix A.2 merge trigger, kNN k=10.
