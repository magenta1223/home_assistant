# Memory 검색 순위와 limit

- 상태: TODO
- 목표: Weekend MVP

## 문제

현재 벡터 검색에 적중한 memory 자체를 제외하고 자식만 반환한다. leaf memory가 검색되면 결과가 비며 요청 `limit`도 무시되고 score가 응답 경계에서 사라진다.

## 계획

1. Qdrant가 반환한 score 순서를 기본 순서로 사용한다.
2. 검색된 memory 자체를 결과 후보에 포함한다.
3. 요청 `limit`을 검증하고 실제 검색과 최종 결과에 적용한다.
4. 중복 ID를 제거하되 최초 score 순서를 보존한다.
5. tree 자식 확장이 필요하면 별도 단계로 두고 depth, child count, cycle을 제한한다.
6. minimum score threshold는 실제 질문 표본에서 relevant/irrelevant score 분포를 확인한 뒤 정한다.
7. score를 결과 모델에 보존해 threshold 조정과 문제 분석에 사용한다.

## 성능 판단

Qdrant는 검색 시 cosine score를 이미 계산한다. threshold 비교의 추가 비용은 사실상 없으며, 필요한 작업은 적절한 threshold를 정하기 위한 오프라인 품질 평가다.

## 완료 조건

- leaf memory가 결과에 포함된다.
- 반환 개수가 요청 limit을 넘지 않는다.
- 결과 순서와 score를 확인할 수 있다.
