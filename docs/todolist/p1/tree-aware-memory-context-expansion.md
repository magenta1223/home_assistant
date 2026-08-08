# Tree-aware Memory context 확장

- 상태: TODO
- 목표: Weekend MVP

## 문제

현재 semantic search는 직접 적중한 memory만 score 순서로 반환한다. 모든 atomic memory가
개별 인덱싱되므로 구체적인 질문은 leaf에 직접 적중할 수 있지만, 넓은 질문이 부모 memory에
적중하면 답변에 필요한 하위 세부사항이 top-K 밖에 남을 수 있다.

과거 구현처럼 검색된 memory를 제외하고 모든 자손을 재귀적으로 반환하면 leaf 결과가 사라지고,
관련성이 낮은 후손까지 포함되며, score와 limit의 의미도 깨진다. 현재 placement 관계 역시 엄밀한
container/detail 타입이 아니라 관련 memory 사이의 single-parent 관계이므로 모든 자손을 세부사항으로
간주할 수 없다.

## 방향

직접 검색 순위는 seed retrieval로 유지하고, 답변 context를 만드는 별도 단계에서만 하위 memory를
제한적으로 확장한다. 검색된 seed 자체는 항상 보존하며 확장 결과로 대체하지 않는다.

## 계획

1. `MemorySearcher`의 직접 적중 결과와 tree context 확장 책임을 분리한다.
2. 자식 후보는 사용자에게 보이는 memory 안에서만 수집한다.
3. 확장 depth, seed당 child 수, 전체 후보 수를 제한하고 visited ID로 cycle을 차단한다.
4. 초기 구현은 direct child 중심으로 시작하고 실제 질문 품질이 확인되기 전에는 모든 후손을 펼치지 않는다.
5. 자식 후보는 부모 score를 상속하지 않고 질문에 대한 semantic score로 다시 평가한다.
6. 직접 적중과 확장 결과의 출처, parent ID, depth를 진단할 수 있게 보존한다.
7. seed와 관련 자식을 중복 제거한 뒤 최종 context limit을 적용한다.
8. Slack과 HTTP의 공통 answer context에서 같은 확장 정책을 사용하도록 `unify-answer-path` 작업과 조율한다.

## 품질 평가

- exact leaf 질문은 기존 직접 검색 품질을 유지해야 한다.
- 넓은 주제 질문은 관련 세부 memory를 추가로 제공해야 한다.
- 관련성이 낮은 sibling과 깊은 후손이 context를 채우지 않아야 한다.
- leaf, 깊은 tree, cycle이 있는 legacy data, PRIVATE child를 포함한 회귀 표본을 둔다.
- self-only, 모든 자손 확장, 제한된 재평가 확장의 precision/recall과 context 크기를 비교한다.

## 완료 조건

- 직접 적중한 memory가 결과에서 사라지지 않는다.
- 부모 적중 시 관련도 높은 하위 memory가 제한된 수로 context에 포함된다.
- 확장 후에도 visibility, cycle, depth, child count, 최종 limit 제약이 지켜진다.
- 확장된 memory의 score와 어떤 seed에서 확장됐는지 확인할 수 있다.
- 넓은 질문의 답변 품질이 개선되고 exact leaf 질문의 품질이 회귀하지 않는다.
