# Memory + Topic 그래프 실행 계획

- 상태: TODO — 설계·실행 계획 조정, 구현 전
- 우선순위: Feature P0 내 Memory 구조 전환 후보. 다른 기능과의 착수 순서는 별도 결정한다.
- 작성일: 2026-10-06
- 수정일: 2026-10-07
- 설계 기준: [Memory와 Topic 그래프 설계](../../../topic-structure-proposal.md), [메모리 구조 판단기준](../../../topic-adoption-criteria.md)
- 다음 작업: 아래 일곱 커밋을 순서대로 구현·검토. `commit` API 조사는 완료했으며 리팩터링은 필수 선행 작업이 아니다.

## 1. 문제와 목표

Memory가 사실과 조직 관계를 함께 가진 현재 구조에서, 사실은 Memory에 두고 조직 관계는 Topic이
맡도록 변경한다. 이번 단계의 중심은 Topic 데이터 구조와 전용 배치다. 검색·응답·화면 전체를
동시에 재설계하지 않는다.

### 확정한 전제

- Source는 한 번 저장하면 불변이다. 별도 Source 버전 모델이나 개정 API를 만들지 않는다.
- Memory는 ACL만 변경할 수 있다. content·evidence 참조·ID·생성 시각은 불변이다.
- 다른 내용은 새 Source와 Memory로 저장한다. 기존 기록을 자동으로 덮어쓰거나 무효화하지 않는다.
- Memory ACL은 `allowedUserIds`를 활용한다. PUBLIC 표현과 원문 열람 권한의 일관성은 별도 설계 사항이다.
- Topic 소속·분류·연결은 Topic이 관리한다. Memory의 `childrenIds`는 제거한다.
- 새 DB로 시작한다고 가정한다. 기존 DB migration·backfill·호환 전환은 구현 범위에 넣지 않는다.
  이 계획 작성은 실제 DB 삭제·초기화나 운영 서비스 실행을 뜻하지 않는다.

## 2. 현재 구현과 변경 지점

사용자 피드백의 번호를 유지한다. 아래 상태는 계획의 처리 방향이며 코드 변경 완료를 뜻하지 않는다.

| 번호 | 현재 구현 | 확인한 동작 | 처리 방향 |
|---|---|---|---|
| 1 | `domain/.../memory/Memory.kt` | content·ACL·evidence와 `childrenIds` 보유 | **변경:** `childrenIds` 제거. content와 근거는 보존하고 소속·연결은 Topic에서 관리 |
| 2 | `SourceRecord.kt`, `SourceDocument.kt` | 저장 record와 분석 요청 묶음 | **설계 반영:** Source 불변, Memory는 ACL만 변경. Source 버전 도입·개정 계획 제거. Source 구조 확장을 선행 작업으로 강제하지 않음 |
| 3 | `SourceRecordRepositoryImpl` | dedup·재분석·ACL 충돌 처리 | **제외:** 재입력·개정·권한 변경 분리나 alias 경로 수정은 이번에 하지 않음 |
| 4 | `MemoryRepository.commit` | Memory 저장 외에 ACL 계산·분석 완료·outbox 저장까지 수행 | **조사:** API의 실제 책임·원자성·이름·소속을 검토. 조사 없이 새 입력 ACL 모델로 교체하지 않음 |
| 5 | `MemoryProposalsPersister`, `MemoryIndexingOutboxProcessor` | Memory 색인 outbox가 이미 구현되어 동작 경로에 연결됨 | **유지:** 기존 outbox 재구현 제외. Topic 작업에 대한 확장 여부는 배치 계약을 정한 후 판단 |
| 6 | `MemoryPlacementService` | Memory 부모·자식 배치와 순환 차단 | **삭제 후 신설:** 기존 tree placement를 제거하고 Topic 전용 배치를 새로 만듦 |
| 7 | `MemorySearcher`, `MemoryAnswerContextProvider` | Memory 직접 검색과 자식 확장 | **부분 제거:** 마지막 단계에서 자식 확장을 제거. Topic 검색·Memory 선택의 새 알고리즘은 보류 |
| 8 | `MemoryConversationContextProvider` | Memory 본문 조합과 최대 8,000자 제한 | **현행 유지:** 원문 context·확인 상태·예산 재설계를 지금 진행하지 않음 |
| 9 | `HandleMemoryConversation` | 10분 session과 기존 request 답변 재사용 | **유지:** 살아 있는 session의 기존 문맥·답변 사용 허용. ACL 변경에 따른 강제 종료·기존 정보 차단을 추가하지 않음. 새 조회에는 현재 ACL 적용 |
| 10 | `VisibleMemoryTree`, HTTP tree API·화면 | Memory forest 표시 | **소비부 정리:** 마지막 단계에서 기존 tree 소비부를 제거·비활성화. 새 Topic 화면·표시 API는 보류 |
| 11 | `DatabaseFactory`, `ApplicationServicesFactory` | 테이블 초기화와 서비스 조립 | **변경:** 확정된 Topic 스키마를 새 DB에 생성하고 Topic 전용 기능 연결. migration 제외 |

## 3. commit API 조사 결과

### 현재 호출 경로

```text
MemoryAnalysisService
  → MemoryProposalsPersister.persist(userId, proposals, analyzedSourceRecordIds)
  → CanonicalMemoryBatchWriter.commit(createdBy, proposals, analyzedSourceRecordIds)
  → MemoryRepository의 한 DB transaction
```

`MemoryProposalsPersister`는 작성자·content·evidence ID 집합으로 안정적인 idempotency key를 만들고
같은 key를 제거한 뒤 writer를 호출한다. 다음 작업들은 `MemoryRepository.commit` 안에 있다.

1. 분석한 SourceRecord가 실제 존재하는지, 각 proposal의 evidence가 해당 batch에 속하는지 검증한다.
2. 기존 idempotency key가 있으면 기존 Memory를 반환한다.
3. 새 Memory라면 evidence의 ACL 교집합을 계산하고 Memory·evidence 연결·독자 목록을 저장한다.
4. Memory 색인 outbox row가 없으면 생성한다.
5. 전달받은 SourceRecord 전체의 상태를 `ANALYZED`로 바꾼다.
6. 저장하거나 재사용한 Memory 목록을 반환한다.

즉, 이 API는 단순한 `saveMemory`가 아니라 **한 source 분석 batch의 결과 확정**이다.
Topic 배치나 실제 벡터 색인 호출은 이 transaction에 들어 있지 않다.

### 파라미터와 원자성이 필요한 이유

`analyzedSourceRecordIds`는 `proposals.evidenceIds`의 합집합과 같지 않을 수 있다. 분석했지만
Memory로 채택할 사실이 없는 record도 분석 완료로 처리해야 한다. proposal이 0개여도 분석한
record를 완료할 수 있다. 따라서 이 인자를 단순히 중복 데이터로 보고 제거하면 안 된다.

Memory 저장만 성공하고 source 분석 완료가 실패하면 재분석이 발생한다. source를 먼저 완료하고
Memory 저장이 실패하면 사실이 저장되지 않았는데 분석은 완료된 상태가 된다. Memory만 저장하고
색인 예약이 실패하면 검색 반영이 빠질 수 있다. 현재 transaction은 이 부분 성공을 막는다.

기존 `CanonicalMemoryBatchRepositoryTest`에는 batch 중간 실패 시 롤백, idempotency 재시도,
색인 실패 재시도와 오래된 worker 결과 방어 사례가 있다. 이번에는 테스트 코드를 읽었으며 실행하지 않았다.

### 검토할 API 문제와 권고 방향

- **이름:** `commit`만으로는 source 분석 완료까지 처리한다는 의도가 잘 드러나지 않는다.
  분석 결과 확정을 나타내는 port·메서드명으로 바꿀지 검토한다.
- **소속:** 구현체 `MemoryRepository`는 읽기, 분석 결과 저장, tree 수정까지 구현한다.
  tree 기능을 삭제하면서 분석 batch 저장 책임의 위치를 검토한다.
- **정책 위치:** ACL 교집합 계산은 domain 연산을 사용하지만 adapter가 호출을 결정한다.
  ACL만 변경 가능한 새 Memory 계약에서 정책 결정과 실제 저장을 어디서 나눌지 검토한다.
  정책을 옮기더라도 읽은 ACL과 저장 사이의 일관성은 보존해야 한다.
- **유지할 경계:** 이름이나 클래스를 정리한다는 이유로 저장 작업을 독립 transaction으로
  나누지 않는다. 일반 CRUD 저장소로 억지로 축소하지 않는다.

조사의 결론은 **원자적인 분석 결과 확정은 이유가 있지만, API 이름과 구현체의 책임은 재검토할
여지가 있다**는 것이다. 이번 조사만으로 API 변경을 확정하거나 코드를 수정하지 않는다.
Source 분석 처리 상태와 불변 원문 자체의 경계도 계약 검토 시 구분하되, 3번에서 제외한 Source
저장소 수정으로 작업을 확장하지 않는다.

관련 코드:

- [CanonicalMemoryBatchWriter](../../../../application/src/main/kotlin/com/homeassistant/application/port/output/memory/write/CanonicalMemoryBatchWriter.kt)
- [MemoryProposalsPersister](../../../../application/src/main/kotlin/com/homeassistant/application/usecase/memory/write/MemoryProposalsPersister.kt)
- [MemoryRepository](../../../../adapter-outbound/src/main/kotlin/com/homeassistant/adapter/outbound/persistence/repo/memory/MemoryRepository.kt)

## 4. outbox 현황과 Topic 배치의 경계

Memory 색인은 **이미 outbox를 사용한다.**

- Memory 저장 transaction에서 `CANONICAL_MEMORY` 대상의 색인 작업을 함께 기록한다.
- `MemoryAnalysisService`가 저장 직후 `processAvailable()`을 호출한다.
- 앱에 연결된 `MemoryIndexingWorker`가 기본 30초 간격으로 처리한다.
- processor는 기본 30초 retry delay, 5분 processing lease와 attempt 검증을 지원한다.
- 실패한 작업과 lease가 만료된 작업은 재처리하고, `reindexAll()`로 전체 Memory를 다시 예약할 수 있다.

반면 현재 tree placement는 `MemoryAnalysisService`가 별도로 직접 호출하고 실패를 로그로 남긴다.
Memory 색인 outbox가 tree placement까지 처리하는 것은 아니다. 따라서 기존 outbox 유지와
향후 Topic 배치의 실패 복구 방법은 구분해서 결정한다.

Topic 배치가 어떤 입력을 받아 무엇을 확정하는지 정하기 전에 새 outbox 종류나 Topic 색인 작업을
추가하지 않는다. Topic 검색·색인 설계 역시 Topic 데이터 구조 확정 이후에 다룬다.

관련 코드:

- [MemoryAnalysisService](../../../../application/src/main/kotlin/com/homeassistant/application/usecase/memory/analysis/MemoryAnalysisService.kt)
- [MemoryIndexingOutboxProcessor](../../../../application/src/main/kotlin/com/homeassistant/application/usecase/memory/write/MemoryIndexingOutboxProcessor.kt)
- [MemoryIndexingWorker](../../../../app/src/main/kotlin/com/homeassistant/app/services/MemoryIndexingWorker.kt)

## 5. 실행 순서 — 일곱 커밋

각 단계는 앞 단계의 검토와 커밋 이후에 진행한다. 동작 변경은 먼저 실패하는 집중 테스트로
계약을 표현하고 구현한다. domain·application은 정책과 흐름을, adapter는 DB·모델 호출을 맡는다.
각 커밋은 해당 범위의 검증을 통과해야 하며, 변경한 use case의 가장 가까운 README를 함께 갱신한다.

### 1 — 문서 범위와 규칙 확정

불변 Source, ACL만 변경 가능한 Memory, Topic의 조직 관계, session 정책과 제외 범위를 기록한다.
`commit` API와 기존 Memory 색인 outbox 조사 결과는 유지한다. 조사 결과가 별도 리팩터링이나
새 outbox의 도입을 강제하지 않는다.

**완료 조건:** 아래 여섯 구현 커밋과 마지막 소비부 제거의 경계가 문서에 명시되어 있다.

### 2 — Topic domain 모델

Topic ID·짧은 설명·분류별 Memory 참조·관련 Topic을 표현한다. 분류명은 Topic 안에서만 의미를
가지며, 한 Memory의 복수 Topic 소속을 허용한다. 관련 관계는 양방향이며 순환을 허용한다.
중복·자기 연결과 참조의 불변 조건을 모델 테스트로 확정한다. 존재·수정 권한 확인 중 저장된
상태가 필요한 부분은 이후 application·저장 계약에서 다룬다.

**완료 조건:** 실제 모델로 설계 예시와 순환 그래프를 표현할 수 있다. 사용자별·ACL 조합별
Topic 복제나 필수 계층을 도입하지 않고, Topic 변경이 Memory와 Source를 수정하지 않는다.

### 3 — Topic port·영속화·스키마

application에 필요한 저장·조회 port를 두고 persistence adapter와 새 DB용 스키마를 구현한다.
분류·복수 소속·양방향 관계를 저장하고 조회한다. 없는 대상·중복·자기 연결과 원자적 변경을
저장 계약으로 검증한다. `DatabaseFactory`에 테이블을 등록하며 기존 DB migration은 만들지 않는다.

**완료 조건:** 임시 DB 테스트에서 관계와 순환을 왕복 복원하고, 변경 실패 시 일부 관계만 남지
않는다. 모델 호출을 DB transaction에 넣지 않는다. 운영 DB나 로컬 관리 런타임을 시작하지 않는다.

### 4 — Topic 전용 배치 use case와 복구 계약

새 배치 use case를 만들고 fake port 테스트로 정상 흐름과 실패 흐름을 먼저 고정한다. Memory의
Topic·분류 소속과 Topic 연결 제안을 받아 존재·권한·중복·처리 누락을 확인하며 순환 검사는 하지
않는다. Topic 전체의 공유 ACL을 만들거나 Agent에게 Memory ACL 변경 권한을 부여하지 않는다.
배치 입력·확정 단위·호출 시점·실패 결과·재처리 방법을 정하고 README에 기록한다.

**완료 조건:** fake 테스트로 성공, 거절, 저장 실패와 재시도의 결과를 설명할 수 있다. 기존 Memory
색인 outbox와 Topic 배치 복구를 구분하고, 새 복구 장치가 필요하면 이 계약에 근거해 최소한으로 구현한다.

### 5 — LLM adapter와 prompt

확정된 배치 port에 모델 adapter와 prompt를 구현한다. 모델 응답의 해석·형식 오류는 adapter가,
배치 정책 검증은 application이 맡는다. 비공개 Memory·Topic 정보를 사용자에게 노출하는 새
검색·표시 경로를 만들지 않는다.

**완료 조건:** 모델 호출을 대체한 테스트로 응답 해석과 실패 처리를 검증하고, 분류별 참조·복수
소속·양방향 관련 관계를 제안할 수 있다. 기존 tree 배치 prompt와 혼합하지 않는다.

### 6 — 새 배치 연결과 기존 Memory tree placement 삭제

`MemoryAnalysisService`와 `ApplicationServicesFactory`에 새 배치 및 정해진 복구 경로를 연결한다.
기존 tree placement 전용 service·port·모델·validator·bootstrap·prompt와 조립을 제거한다.
기존 클래스에 Topic 분기를 덧붙이지 않는다. Memory 저장과 색인 outbox의 원자성·재시도를 유지한다.

**완료 조건:** 입력 분석 이후 Topic 배치가 호출되고 실패 시 4단계 계약대로 복구할 수 있다.
기존 Memory tree placement는 호출 경로와 구현에서 제거된다. 아직 남은 `childrenIds` 소비부는
다음 단계에서 함께 제거하며 이 중간 커밋도 컴파일과 관련 테스트를 통과한다.

### 7 — `childrenIds`와 legacy 소비부 완전 제거

Memory의 `childrenIds`, 저장 컬럼, 직렬화·복원 코드와 관련 테스트를 제거한다. 이를 사용하던
검색 자식 확장과 `VisibleMemoryTree`·HTTP tree API·tree 페이지 및 이동 링크를 함께 제거한다.
공유 인증 asset은 다른 페이지에서 사용하는 제공 경로를 유지한다. Topic 검색을 구현하기 전까지
Memory 직접 검색을 유지하고 answer context에서 자식 확장만 제거한다. 따라서 기존 tree 화면과
API는 더 이상 제공하지 않으며, 직접 조회·검색은 현재 ACL을 확인하는 경계를 유지한다.

이는 새 Topic 검색 알고리즘·Memory 선택 알고리즘·Topic UI·표시 API를 구현하는 단계가 아니다.
삭제한 관계를 흉내 내는 빈 tree나 모든 Memory를 루트로 나열하는 호환 tree를 반환하지 않는다.
참조를 남겨 빌드를 깨뜨리지 않고, 삭제된 tree 기능으로 이동하는 경로나 호출도 정리한다.
conversation context의 본문 구성·예산과 session 정책은 유지한다.

**완료 조건:** 실행 코드에 `childrenIds`와 tree 전용 소비부가 남지 않고 전체 변경이 컴파일된다.
관련 회귀 테스트가 통과하며 새 Topic 검색·화면은 후속 작업으로 남아 있음을 명확히 기록한다.

## 6. 보류·제외 범위

| 항목 | 현재 처리 |
|---|---|
| Source repository의 dedup·alias·재입력 흐름 수정 | 하지 않음. 불변성 합의를 이유로 이 작업을 다시 추가하지 않음 |
| Source 버전·개정 API | 도입하지 않음 |
| 기존 데이터 migration·backfill·호환 전환 | 새 DB 전제로 제외 |
| Topic 검색·Memory 선택·추가 탐색 | Topic 데이터 구조 확정 이후에 별도 계획 |
| conversation context·원문 확인 흐름 변경 | 현재 구현 유지. 설계 문서의 최종 목표와 현재 구현 범위를 구분 |
| Topic 화면·표시 API | 지금 고려하지 않음 |
| ACL 변경 API와 Source 열람 권한 연결 | ACL 가변성은 설계에 반영하되 저장·변경 계약은 미정. 별도 ACL 모델을 선행 조건으로 강제하지 않음 |
| provider 문맥·저장 답변의 철회 정책 | 살아 있는 session에서는 계속 사용. ACL 변경에 따른 session 강제 종료·기존 답변 차단·session 무효화용 revision 도입 제외 |
| 자동 분할·병합·대규모 성능 검증 | 후속 단계. 사용자 1만 명이라는 설계 목표를 이번 구현 완료로 표시하지 않음 |

### 살아 있는 session의 문맥 재사용

2026-10-07 합의: session이 살아 있는 동안은 이미 전달된 문맥과 생성된 답변을 계속 사용한다.
권한 철회가 발생해도 session을 강제 종료하거나 기존 문맥·답변을 차단하지 않는다. 새 Memory
검색과 원문 조회에는 현재 ACL을 적용한다.

기존 10분 idle lease를 유지한다. session이 만료·종료되면 그 문맥을 새 session에 넘기지 않고
현재 ACL에 따라 정보를 다시 조회한다. 이미 전달된 메시지나 사용자가 읽은 정보는 소급 회수하지
않는다. 이 정책을 이유로 request dedup을 제거하거나 같은 요청을 provider에 중복 전송하지 않는다.

application `UserId`, 요청 dedup, 10분 conversation idle lease를 유지한다. Slack 기능은 추가하지
않으며 [Memory DB 조회 최적화](../../p2/memory-query-performance.md)는 사용자 학습 과제로 남긴다.

## 7. 검증과 완료 조건

- [ ] Topic 데이터 구조·저장 관계·변경 계약 확정.
- [x] 현재 `commit`과 outbox 흐름 조사. API 변경안 채택은 미정.
- [ ] Topic 모델·저장소·서비스 조립 구현.
- [ ] 기존 tree placement 삭제와 Topic 전용 배치 구현.
- [ ] legacy 자식 확장·tree 소비부 정리와 함께 `childrenIds` 완전 제거. 새 검색·UI 구현 제외.
- [ ] Topic 변경이 불변 Source와 Memory content·근거·ACL을 변경하지 않는 회귀 검증.
- [ ] 복수 소속·양방향 관계·순환 허용·자기 연결·중복·없는 대상·저장 실패 검증.
- [ ] 변경한 use case의 가장 가까운 README와 실제 검증 결과 기록.

코드 변경 시 관련 테스트 클래스부터 실행하고 해당 module test로 넓힌다. 모델 호출은 fake,
저장은 임시 DB로 검증한다. 원자 저장이나 outbox를 변경한다면 기존 롤백·중복 방지·재시도 테스트를
함께 확인한다. 문서 수정만으로 application 테스트나 운영 서비스를 실행하지 않는다.

## 현재 상태 (2026-10-07)

사용자 피드백 1~11번을 반영해 설계 문서와 계획 범위를 조정했다. `commit` 호출부·구현·기존 테스트와
outbox processor·worker 연결을 읽어 조사 결과를 기록했다. 살아 있는 session의 기존 문맥·답변
재사용을 허용하는 정책과 일곱 커밋의 실행 순서를 반영했다. 마지막 커밋의 legacy 소비부 정리는
필수 제거 범위이며 새 Topic 검색·UI 개발은 보류임을 명시했다. 문서 단계의 `git diff --check`를
통과했으며 baseline `:domain:test :application:test`는 통과했다(Gradle up-to-date). 애플리케이션
코드 변경, DB 초기화·migration, 운영 조회·배포는 수행하지 않았다.

### 2단계 진행 (2026-10-07)

Topic과 정규화된 양방향 TopicRelation domain 모델을 구현했다. 분류 내 중복은 거절하며 분류 간·
Topic 간 복수 소속과 순환 관계를 허용한다. 구체적인 모델 결정은 설계 문서에 기록했다.
먼저 새 domain 테스트만 작성한 상태에서 `:domain:test`가 미구현 타입 참조로 실패함을 확인했고,
모델 구현 후 같은 검증을 통과했다. 저장·배치·기존 Memory 변경은 이후 단계에 남아 있다.

### 4단계 완료 (2026-10-07)

서브에이전트가 배치 use case와 조건부 저장·미배치 조회를 구현한 뒤 사용량 한도로 중단됐다.
orchestrator가 코드와 실패·재시도 테스트를 리뷰했고 집중 application·persistence 테스트 및
architecture 검증을 통과했다. 배치 호출·worker 연결은 아직 없으며 6단계 범위다. 사용자 승인 후
orchestrator가 직접 구현을 이어받아 실패 단계·예외 종류만 기록하는 안전한 진단과 테스트 가독성을
보완했다. 모델의 원문·오류 메시지는 로그에 남기지 않는다.

### 3단계 진행 (2026-10-07)

TopicMaintenanceStore port, 새 DB용 Topic·소속·관계 테이블, repository와 factory 등록을 구현했다.
한 transaction의 생성·소속 추가·연결 추가만 제공하며 혼합 ACL Topic 전체를 덮어쓰지 않는다.
생성 key 재시도·충돌, 대상 존재, 양방향 중복·순환과 실패 롤백을 검증했다. creator가 공유 범위에
없는 입력도 내부 정리할 수 있으며, 내부 조회를 외부 검색·응답으로 사용하지 않는 경계를 명시했다.

새 저장소 테스트를 먼저 작성해 미구현 타입으로 실패하는 것을 확인했고, 구현 후
`:adapter-outbound:test --tests '*TopicRepositoryTest'`와
`:application:test --tests '*ApplicationPackageArchitectureTest'`를 통과했다. Source·Memory 값과
숨겨진 기존 소속 보존도 테스트했다. 배치 use case·모델 호출·복구 실행 경로는 아직 구현하지 않았다.
