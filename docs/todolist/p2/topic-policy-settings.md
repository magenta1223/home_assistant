# Topic policy 설정값을 composition root로 모으기

- 상태: TODO
- 우선순위: P2
- 선행 작업: [Topic·Group 병합과 kNN 연결](../p1/topic-group-merge-and-knn-links.md)

## 문제

Group policy의 임계값은 `GroupPolicySettings` 하나로 묶여 있고, 앱 factory가 `groupSettings` 인자로 받아
placement·split·merge policy에 넘긴다. Topic policy는 설정 객체가 없다.

- `TopicPlacementPolicy(joinThreshold, joinAllThreshold, fillUpTo)`, `TopicDescriptionRefreshPolicy(growthThreshold)`가
  각자 생성자 기본값으로 값을 가진다.
- factory는 `AssignMemoryToTopics(store, embeddings)`만 호출하므로 앱에서는 항상 기본값이 쓰이고, Topic 설정을 넣을 자리가 없다.

임계값은 실데이터로 조정할 config 값이므로 Group처럼 composition root에서 한 번에 보이고 주입되어야 한다.

## 목표

- Topic 설정값을 settings 객체로 묶고 factory 인자(`topicSettings`)로 주입한다. 검증은 settings의 `init`에 둔다.
- 배정 값(3개)과 설명 재생성 값(1개)은 쓰임새가 다르다. 한 객체로 묶을지 나눌지 설계 때 정한다.
- domain은 계속 환경변수를 읽지 않는다.

## 완료 조건

- factory에서 Group·Topic 설정이 같은 방식으로 보인다.
- 기존 기본값과 동작이 같다. 전체 테스트 통과.
