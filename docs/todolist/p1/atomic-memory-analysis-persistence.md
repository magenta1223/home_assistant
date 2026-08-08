# Memory 분석 결과 저장의 원자성과 idempotency

- 상태: TODO
- 우선순위: P1
- 선행 작업: P0 인덱싱·배치 durable retry 설계 확정

## 문제

`MemoryProposalsPersister`는 proposal을 하나씩 별도 transaction으로 저장한 뒤 source record를 별도
transaction에서 `ANALYZED`로 바꾼다. batch 중간 또는 source 상태 갱신에서 실패하면 일부 memory만
남고 source는 `PENDING`일 수 있다. 같은 import를 재시도하면 이미 저장된 memory가 다시 생성될 수 있다.
현재 memory에는 동일 분석 결과를 식별하는 안정적인 idempotency key가 없다.

## 원칙

- LLM 호출은 DB transaction 밖에서 수행한다.
- 분석 결과의 canonical DB 반영은 하나의 짧은 transaction으로 수행한다.
- source record 상태, memory, evidence 관계, projection outbox는 함께 commit된다.
- 단순한 content 문자열만으로 중복 여부를 판단하지 않는다.

## 구현 계획

1. 여러 memory와 evidence를 한 transaction에서 저장하는 batch output port를 정의한다.
2. 저장 요청에 분석 대상 source record ID 집합과 proposal별 안정적인 idempotency key를 포함한다.
3. key는 정규화된 memory 필드와 정렬된 evidence ID 또는 명시적인 analysis-run/proposal identity로 만들고 선택 근거를 문서화한다.
4. DB에 idempotency key unique constraint를 추가한다.
5. 하나의 transaction에서 다음을 순서대로 수행한다.
   - 기존 key 조회 및 중복 제외
   - 신규 memory와 evidence 저장
   - 필요한 projection outbox 생성
   - 대상 source record를 `ANALYZED`로 변경
6. 재시도 시 기존 memory를 재사용하고 누락된 memory만 추가하도록 결과 계약을 정한다.
7. `MemoryProposalsPersister`의 proposal별 write orchestration을 batch persistence로 교체한다.
8. transaction이 커지지 않도록 embedding, Qdrant, Codex placement 호출이 포함되지 않는지 구조 테스트로 고정한다.

## 회귀 테스트

- 두 번째 proposal 저장 실패 시 첫 번째 memory도 commit되지 않는다.
- evidence 저장 실패 시 memory와 source 상태가 함께 rollback된다.
- source 상태 갱신 실패 시 memory와 outbox가 남지 않는다.
- commit 성공 후 응답 전달 실패로 같은 요청을 재실행해도 memory가 중복되지 않는다.
- 같은 content라도 evidence 또는 의미 필드가 다른 합법적인 memory는 보존된다.

## 완료 조건

- 하나의 분석 batch가 전부 commit되거나 전부 rollback된다.
- 성공한 batch를 재실행해도 canonical memory 수가 증가하지 않는다.
- `ANALYZED` source record는 해당 batch의 canonical memory와 projection 작업을 항상 가진다.
- persistence 실패 재시도 테스트가 실제 SQLite repository로 통과한다.

## 제외 범위

- 서로 다른 import에서 의미적으로 유사한 memory를 LLM으로 병합하는 기능
- 기존 전체 memory의 사후 semantic deduplication
