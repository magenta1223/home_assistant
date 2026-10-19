# Topic을 component로 사용하는 Group 생성·분할

- 상태: DONE (2026-10-19)
- 작성일: 2026-10-10
- 구체화: 2026-10-19 — hexagonal architecture와 SRP에 따른 책임·port·검증 경계
- 우선순위: Feature P0 — 현재 사용자 요청의 다음 구현 범위
- 기준: [메모리 구조 판단기준](../../topic-adoption-criteria.md), [현재 구조 설계](../../topic-structure-proposal.md)
- 아래 계획을 구현했다. 실제 결과와 검증은 10절에 기록한다.

## 1. 목표와 확정한 합의

이미 저장된 Topic을 xMemory의 component에 대응시키고, Topic들을 Group으로 조직한다.
새 Topic을 기존 Group에 배치하거나 새 Group을 만들며, 커진 Group을 분할한다.

```text
Source ← evidence ─ Memory ← 복수 소속 ─ Topic ← 단일 소속 ─ Group
                                         ↔ TopicRelation
```

- Memory는 여러 Topic에 속할 수 있다. Memory를 많이 공유하는 Topic도 유사한 별개 Topic으로 취급한다.
- 배치가 완료된 Topic은 정확히 하나의 Group에 속한다. 미처리 Topic은 아직 Group이 없을 수 있다.
- Group 분할은 Topic의 Group 소속만 바꾼다. Topic 본문·분류·Memory 소속·TopicRelation을 바꾸지 않는다.
- 이번에는 Group 생성·배치·분할을 구현한다. Group 병합 정책과 자동 실행은 구현하지 않지만 저장 계약은 병합을 수용한다.
- Topic 자체도 장기적으로 분할 대상이다. 이번에는 Topic 크기가 적절하다고 가정한다.
- 순수성만으로 Topic 크기를 제한할 수 없으며, 순수성 판단 자체도 어렵다는 점을 후속 과제로 남긴다.
- 이번 범위를 이유로 Topic 크기 상한이나 Memory 추가 거절 정책을 도입하지 않는다.

Group은 Topic 위의 한 단계 묶음이다. 재귀 Group 계층이나 Topic 부모·자식 트리를 만들지 않는다.
기존 Topic 그래프와 Memory의 복수 소속은 유지한다. 이전 Memory→Group 제안의 부활이 아니다.

## 2. 현재 코드와 이번 범위의 경계

현재 Topic domain, 분류별 Memory 참조, TopicRelation, 명시적 원자 저장이 존재한다.
`TopicMaintenanceStore`는 trusted internal 경계이며 생성과 소속·관계 추가를 지원한다.
LM Topic 생성과 자동 배치는 제거되어 현재 앱에는 Topic을 자동 공급하는 경로가 없다.

따라서 첫 입력은 명시적으로 저장된 Topic이다. Memory 생성 결과를 억지로 Topic으로 변환하거나
삭제한 LM 생성 경로를 복원하지 않는다. Topic 생성 방식은 별도 작업이다.

이번 완료 범위는 내부 use case 호출로 미배치 Topic을 읽어 Group을 만들고 분할한 뒤,
재시작 후에도 저장된 Group을 조회하고 남은 Topic 처리를 이어갈 수 있는 단계다.
앱 factory에 조립하되 주기 worker, 외부 HTTP API, UI는 추가하지 않는다.
실제 Topic 공급 경로가 생기면 그 경로에서 호출하거나 주기 실행을 연결한다.

### Hexagonal architecture와 SRP 적용

의존 방향은 `adapter → application → domain`이다. `app`은 구체 구현을 조립한다.
Domain 정책은 값만 입력받아 결정 결과를 반환하며 저장소, 임베딩 호출, 설정 환경변수,
transaction, 재시도, logging에 의존하지 않는다. Application이 outbound interface를 소유한다.

| 위치 / 구성 요소 (이름은 기본안) | 단일 책임 | 맡기지 않는 일 |
|---|---|---|
| `domain/group/Group`, `TopicPartition` | Group 및 소속 재구성의 불변조건 | DB revision·operation receipt 관리 |
| `domain/group/TopicVector`, `GroupGeometry` | 벡터 유효성, cosine·centroid 계산 | 임베딩 생성·캐시 조회 |
| `domain/group/GroupPlacementPolicy` | 기존 Group 선택 또는 새 Group 결정 | 분할·저장 |
| `domain/group/GroupSplitCandidateGenerator` | 연결 성분과 결정적 fallback으로 분할 후보 생성 | 후보 점수 비교·외부 조회 |
| `domain/group/GroupOrganizationScorer` | 동일 Topic 집합의 조직 점수 계산 | 분할 실행·저장 |
| `domain/group/GroupSplitPolicy` | 후보 평가와 크기 제약에 따른 최종 분할 선택 | 임베딩·DB 접근 |
| `domain/group/GroupOrganizationPlanner` | 순수 정책을 조합하여 한 Topic의 배치·분할 계획 반환 | 실행·재시도·영속 ID 발급 |
| `application/usecase/group/OrganizePendingTopicsService` | 처리량 제한, Topic별 실행과 결과 집계 | 벡터 연산·군집화·SQL |
| 같은 패키지의 `OrganizeTopic` | 한 Topic의 snapshot 조회→정책 판단→확정 및 충돌 재시도 | 점수 공식·transaction 구현 |
| 같은 패키지의 `PrepareTopicEmbedding` | 현재 설명·임베딩 규약에 맞는 벡터 재사용 또는 생성 | 배치 결정·Group 저장 |
| `adapter-outbound/embedding/topic` | Topic embedding port를 기존 `TextEmbedder`에 연결 | Group 정책·캐시 수명 결정 |
| `adapter-outbound/persistence/repo/group` | 일관된 snapshot 조회와 원자적 변경 확정 | 배치·분할 후보 선택 |
| `app/services/ApplicationServicesFactory` | port와 adapter 및 정책 설정 조립 | 실제 조직 판단 |

분리 기준은 변경 이유다. 알고리즘 교체, 실행·복구 변경, 저장 기술 변경이 서로 번지지 않게 한다.
순수 정책마다 interface/implementation 쌍을 만들지는 않는다. 외부 경계에는 port를 두고,
알고리즘은 구체적인 순수 타입으로 조합한다. 작은 계산은 관련 타입의 메서드로 유지한다.
새 코드에는 framework annotation·DB entity·provider payload·serialization annotation이
domain/application으로 들어오지 않게 한다. 기존 모듈 전체의 기술 결합 정리는 별도 범위다.

### Application port 계약

| Port | 계약 |
|---|---|
| input `OrganizePendingTopics` | `execute(limit)` → Topic별 성공·이미 처리됨·실패·충돌 소진 결과 |
| output `PendingTopicSource` | Group 미소속 Topic의 ID와 설명을 ID 순으로 제한 조회 |
| output `TopicEmbeddingGenerator` | 설명을 정해진 입력 규약으로 임베딩하고 모델·규약 식별자와 함께 반환 |
| output `TopicEmbeddingStore` | Topic ID·설명 fingerprint·모델·규약으로 벡터 조회 및 재사용 가능하게 보존 |
| output `GroupSnapshotReader` | 단일 시점의 Group 소속·revision·centroid와 평가에 필요한 Topic 벡터 조회 |
| output `GroupChangeWriter` | `apply(change)`로 소속 재구성을 원자 확정하고 적용 결과 반환 |

5절의 `GroupMaintenanceStore`는 개념적 저장 경계다. 구현에서는 읽기와 쓰기 역할을 위 두
port로 구분하되 하나의 persistence adapter가 둘을 구현해도 된다. Generic CRUD repository,
범용 UnitOfWork, application에서 여닫는 transaction은 도입하지 않는다.
`PendingTopicSource`는 Group 작업 전용 projection이므로 기존 Topic 저장 port를 비대하게 만들지 않는다.

벡터 캐시 저장은 Group 확정과 별개다. 임베딩 성공 후 배치가 실패하면 유효한 캐시만 남을 수
있으며 다음 호출이 재사용한다. 반면 Group 소속·centroid·revision·적용 receipt는 반드시
같이 저장한다. 저장 adapter는 현재 소속을 읽고 domain의 partition 검증을 호출한 뒤 DB 제약으로
경쟁 접근을 막는다. 비즈니스 불변조건을 SQL adapter 안에만 구현하지 않는다.

## 3. 구현 기본안: 표현과 소속

### Group 모델

- `Group`: 양수 ID, 비어 있지 않은 중복 없는 Topic ID 집합.
- 저장 snapshot에는 경쟁 갱신을 확인할 revision을 포함한다.
- Group은 ACL·사실 본문·LM 요약을 갖지 않는다. 검색용 설명이 필요하면 이후 별도로 설계한다.
- Topic은 기존 모델을 유지한다. 역방향 Group 소속은 저장소가 조회한다.
- 저장소는 Group 본체와 소속을 저장하고 `topicId`의 전역 유일 제약으로 단일 Group 소속을 보장한다.

### Topic과 Group 벡터

첫 기본안은 Topic의 `description`을 component text로 사용한다. 기존 embedder를 outbound
adapter에서 재사용하고 application에는 목적이 명확한 Topic embedding port를 둔다.
기존 Memory 벡터 평균을 Topic 벡터로 쓰는 방안과 혼합하지 않는다.

- Topic 벡터는 정규화하고 Group centroid는 소속 Topic 벡터의 동일 가중 평균으로 계산한다.
- 같은 Memory를 공유해도 Topic ID가 다르면 각각 계산한다. Memory 중복률로 Topic을 합치지 않는다.
- Topic 벡터는 Topic ID, 설명 fingerprint, 임베딩 모델·입력 규약 식별자와 함께 재사용한다.
- 모델·차원·입력 규약이 다른 벡터를 섞지 않는다. 변경 시 재구성이 필요함을 명시적으로 반환한다.
- 비정상 차원, NaN/무한대, 영벡터와 계산 불가능한 centroid는 저장 전에 실패시킨다.
- Group centroid와 소속은 같은 확정 작업으로 저장한다. Qdrant를 Group 저장의 필수 경계로 추가하지 않는다.

현재 vector store는 upsert/search만 제공하고 벡터 ID 조회는 없다. 이 기본안은 Memory 벡터
조회 API를 확장하지 않고 Topic 전용 계산으로 시작한다. Topic 설명이 범위를 충분히 표현하는지는
fixture로 확인할 가정이다. 빈 Topic도 유효한 설명이 있으면 배치할 수 있다.

## 4. 배치와 분할 정책

### 기존 Group 배치 / 새 Group

1. 미배치 Topic을 ID 순서로 제한된 수만 읽는다.
2. 임베딩은 DB transaction 밖에서 준비한다.
3. 현재 Group centroid 중 cosine이 가장 높은 Group을 선택한다.
4. attach 임계값을 초과하면 추가하고, 아니면 새 Group을 만든다. 동점은 Group ID 순서로 결정한다.
5. 추가 후 크기 제한을 초과하면 저장 전에 분할 결과까지 계산한다.

초기 기본값 후보는 attach `0.6`, Group 크기 `12 Topic`, 분할 연결 유사도 `0.65`다.
논문 실험값을 가져온 출발점이며 한국어 Topic과 현재 임베딩에서 검증된 기준은 아니다.
설정으로 분리하고 경계값을 테스트한다. 최초 Group 생성과 singleton Group을 허용한다.

### 분할 후보와 선택

분할 대상 Group의 Topic 벡터로 유사도 임계값 그래프를 만들고 연결 성분을 후보로 사용한다.
연결 성분이 하나뿐이거나 여전히 큰 경우에도 종료할 수 있도록 크기 제한을 만족하는 대안을 만든다.

우리 기본안의 fallback은 먼 두 Topic을 seed로 삼고 가까운 쪽으로 나누는 결정적 이분할이다.
양쪽이 비어 있지 않도록 하며 완전히 같은 벡터 등 동점에서는 Topic ID로 균형을 맞춘다.
상한을 넘는 조각에만 반복 적용한다. 각 단계에서 최대 조각 크기가 감소해야 한다.
이는 공개 구현의 PCA 기반 fallback을 그대로 이식하는 것이 아닌 우리 쪽 단순화다.

첫 구현의 후보 집합은 다음 두 가지로 제한한다. 같은 partition은 Topic ID 정렬로 정규화해 중복 제거한다.

1. 연결 성분으로 나눈 뒤 상한 초과 조각에만 fallback을 적용한 결과.
2. 대상 Group 전체에 fallback을 적용한 결과.

연결 edge는 cosine `>= splitThreshold`, 배치는 cosine `> attachThreshold`로 정의한다.
분할 결과의 점수가 같으면 Group 수가 적은 후보, 이후 정렬한 Topic ID partition 순서로 선택한다.
fallback은 서로 다른 두 seed를 각 조각에 고정해 비어 있는 조각을 방지한다.

후보 평가는 xMemory의 `SparsityScore + SemScore` 구조를 Topic 단위에 적용한다.
크기 항, Topic과 Group centroid의 응집도, Group centroid 사이의 기하를 구분한다.
동일 평가 대상 Topic 집합과 동일한 주변 Group snapshot으로 모든 대안을 비교한다.
빈 집합·Group 하나·동일 centroid에서 점수가 정의되도록 경계 규칙을 수치 테스트로 고정한다.
이때 이웃이 없는 단일 Group의 기하 가중치는 1로 두는 것을 기본안으로 한다.

분할 전 비교 기준에도 incoming Topic을 이미 추가해 Topic 수를 같게 유지한다. 점수 계산은
Double로 수행하고 식·epsilon·동점 규칙은 수치 fixture로 고정한다. 빈 조직의 점수는 0,
단일 Group의 기하 가중치는 1, MAD가 0이면 양의 epsilon을 사용한다. 유효하지 않은
centroid를 가진 후보는 제외하며 유효한 후보가 없으면 명시적인 계산 실패로 반환한다.

**이번 기본안에서는 최대 크기를 강제한다.** 제한을 만족하는 후보 중 점수가 가장 좋은 것을
선택하며, 모두 기존 구조보다 점수가 낮더라도 크기 제한을 지킨다. 강제 분할 여부와 점수 변화를
결과에 남긴다. 크기 이하 Group은 점수만을 이유로 계속 분할하지 않는다.
따라서 점수 개선만 허용하는 정책과 혼동하지 않는다. singleton 자동 병합은 없다.

첫 버전은 현재 Group 벡터 snapshot을 읽어 정확 비교한다. 분할 후보 생성은 해당 Group 안에서만
수행하고 점수는 같은 전체 Group snapshot 기준으로 비교한다. ANN·증분 점수 최적화는 하지 않는다.
이 방식의 비용은 Group 수에 따라 증가한다. 한 번의 처리량은 제한하지만 전체 규모에 대한
비용 상한이나 사용자 1만 명 성능을 보장했다고 주장하지 않는다.

## 5. 병합까지 수용하는 저장 API

이름은 구현 때 기존 스타일에 맞추되 핵심 계약은 `GroupMaintenanceStore.apply(change)`다.
범용 그래프 편집 API나 비어 있는 `merge()` 메서드를 추가하지 않는다.

```text
GroupChange
  operationKey
  expectedGroups: 기존 Group ID + revision
  incomingTopicIds: 아직 미배치인 Topic
  resultingGroups: 유지할 Group ID 또는 새 Group key + 최종 Topic 집합
```

이 계약으로 신규 생성 `0→1`, 추가 `1→1`, 분할 `1→N`, 후속 병합 `N→1`을 표현한다.
병합 후보를 만들고 선택하는 정책은 이번에 구현하지 않는다. 저장 계약의 `N→1` 지원은 테스트한다.

원자 저장은 다음을 보장한다.

- 입력은 기존 대상 Group 전체 소속과 incoming Topic의 합집합이며, 결과가 그 집합을 정확히 분할한다.
- Topic 누락·중복, 없는 Topic, 다른 Group에서 소속 훔치기, 빈 결과 Group을 거절한다.
- 대상 Group revision과 incoming Topic의 미배치 상태를 transaction 안에서 확인한다.
- 기존 ID를 유지할 Group은 revision을 올리고, 결과에 없는 대상 Group은 제거한다.
- 분할 시 최소 Topic ID를 포함한 조각이 기존 Group ID를 유지하는 결정적 규칙을 사용한다.
- 새 Group ID, 소속, centroid, 재시도 결과는 함께 확정한다. 실패하면 전부 롤백한다.
- 같은 operation key와 같은 요청은 같은 결과를 반환한다. 다른 요청 내용의 key 재사용은 거절한다.
- 재시도 결과 조회를 revision 검사보다 먼저 처리해, 이미 성공한 작업의 재전송을 충돌로 오인하지 않는다.
- 결과는 `Applied`, `AlreadyApplied`, `Conflict` 등 의미 있는 타입으로 구분한다.

`GroupChange`에는 결과 Group의 centroid와 그 계산에 사용한 벡터 식별자도 포함한다.
저장 시 같은 벡터·규약인지 확인한다. revision과 operation key는 application 저장 계약에 두고,
domain의 분할 결과는 아직 발급되지 않은 DB ID 대신 Topic partition으로 표현한다.
같은 확정 요청의 재전송은 operation key를 유지하고, 충돌 후 새 snapshot으로 다시 판단한
새 요청에는 새 key를 사용한다. 예상 충돌과 잘못된 요청을 구분하며 모든 예외를 충돌로 감추지 않는다.

임베딩 호출이나 후보 계산을 저장 transaction 안에서 수행하지 않는다. application이 변경을
결정하고 adapter는 원자성·경쟁 갱신·참조 무결성을 책임진다. 재구성 대상 밖의 Group 변화로
점수가 달라질 수는 있지만 대상 Group의 소속을 덮어쓰는 것은 허용하지 않는다.

## 6. 실행·실패·복구

내부 input port는 `organizePendingTopics(limit)`처럼 처리 범위가 제한된 작업을 제공한다.
첫 버전은 순차 처리하며, Topic 하나의 배치와 필요한 분할을 하나의 확정 단위로 삼는다.

- 미배치 여부는 Group 소속 부재로 다시 찾는다. 별도 자동 worker나 새 outbox는 만들지 않는다.
- 임베딩 실패 시 Group을 일부 저장하지 않고 실패 Topic과 이유를 결과에 남긴다.
- 호출 중 뒤의 독립 Topic도 정해진 예산 안에서 처리한다. 다음 호출에서는 미처리 Topic을 재시도한다.
- DB 경쟁 충돌은 최신 snapshot으로 제한된 횟수만 다시 계산하고, 초과 시 다음 호출로 넘긴다.
- 저장 성공 직후 중단돼도 다음 조회에서 이미 배치된 Topic을 중복 배치하지 않는다.
- 메모리 색인 outbox, Memory 분석 성공 여부, conversation 요청 dedup·10분 idle lease와 분리한다.
- 영구 실패 Topic이 batch를 독점할 수 있는 반복 운용 문제는 실제 worker 도입 시 격리·backoff와 함께 다룬다.

모델·입력 규약 불일치처럼 조직 전체에 영향을 주는 실패는 `RebuildRequired`로 이번 실행을
중단한다. Topic 하나의 임베딩 실패는 해당 항목에 기록하고 계속한다. coroutine cancellation은
실패 결과로 삼키지 않는다. 저장 결과가 불확실한 재전송은 같은 operation key로 확인한다.

미처리는 자동 복구 완료라는 뜻이 아니다. 이번 단계에서는 다음 내부 호출로 복구할 수 있음을
검증하며, 무인 주기 재시도까지 구현한 것으로 기록하지 않는다.

## 7. 구현 순서와 완료 조건

| 단계 | 작업 | 확인할 결과 |
|---|---|---|
| 1 | Group 모델·소속·재구성 계약과 정책 설정 | 단일 Group 소속, 분할·병합을 표현할 수 있는 입력·결과 |
| 2 | 순수 배치·분할·점수 계산 | 고정 벡터로 attach/new, 경계값, 결정성, 동일 벡터 fallback, 종료 검증 |
| 3 | Application port·orchestration과 Topic embedding adapter | fake port로 실행·실패·재시도 검증; 벡터 재사용, 모델 혼용·비정상 벡터 거절 |
| 4 | Group 저장 adapter와 스키마 | 원자 저장, stale revision, 중복 요청, 실패 롤백, `N→1` 계약 검증 |
| 5 | 앱 factory·설정·스키마 초기화 연결 | 임시 DB에서 생성→배치→분할→재시작→재호출 흐름 |
| 6 | 보존 회귀와 문서화 | 공유 Memory·TopicRelation·Topic 내용·Source·ACL 불변 확인 |

새 use case와 output port의 가장 가까운 README에 호출 순서, 신뢰 경계, 실패·복구 계약을 기록한다.
테스트는 domain/application의 고정 벡터부터 시작하고 임시 DB 통합 검증으로 넓힌다.
구현 완료 시 관련 모듈과 필요한 전체 회귀를 실행한다. 실제 LM·운영 서비스는 호출하지 않는다.

Domain 테스트는 adapter나 mock 없이 실행한다. Application 테스트는 fake port를 사용해
정책 계산과 외부 작업의 순서, 임베딩 실패 시 Group write 미호출, 충돌 시 snapshot 재조회,
batch limit을 검증한다. DB 테스트는 새 테이블의 제약과 원자성을 확인하고 실제 임베딩 서버에
의존하지 않는다. 새 schema는 `DatabaseFactory`의 초기화 목록, 저장 port는 `RepositoryStores`와
`RepositoryFactory`, input port는 `ApplicationServices`에 연결한다. 조립은 자동 실행을 뜻하지 않는다.

아키텍처 검토에서는 domain의 application/adapter 참조, application의 adapter/framework 참조,
adapter의 배치 판단, service 내부의 군집 계산이 없는지 확인한다. 이를 위해 새 아키텍처 검사
프레임워크나 repository 전체 리팩터링을 추가하지 않는다.

추가 확인 사례:

- 같은 Memory를 공유하는 별개의 Topic이 서로 다른 component로 보존된다.
- 분할 전후 Topic ID 합집합이 같고 Group 사이 Topic 중복이 없다.
- 임베딩 준비 중 오류와 저장 중 오류 모두 기존 조직을 훼손하지 않는다.
- 동시에 같은 미배치 Topic을 처리해도 소속이 두 개 생기지 않는다.
- 병합이 없어 singleton Group이 남는 것을 정상 결과로 취급한다.
- 작은 의미 fixture에서 원하는 분리와 원치 않는 파편화를 관찰한다. 점수 상승을 검색 품질 향상으로 동일시하지 않는다.

## 8. 보류 사항과 알려진 한계

- **Topic 자체 분할:** 순수해도 무한히 커질 수 있고 순수성도 판정하기 어렵다. 이번 크기 가정은 영구 도메인 규칙이 아니다.
- Topic 생성·Memory 배치·Topic 설명 갱신. 이후 설명 변경 시 벡터 무효화와 Group 재배치 계약도 함께 설계한다.
- Group 병합 정책·주기 정리. 저장 API만 수용하며 자동 병합은 실행하지 않는다.
- Group 검색·Topic 선택·Memory 선택·원문 확장·predictive entropy 계산.
- Group 및 component kNN 탐색 링크. 분할용 임시 유사도 그래프와 영속 TopicRelation을 혼동하지 않는다.
- Group/Topic 설명·연결에 섞인 비공개 정보를 사용자에게 안전하게 투영하는 경로.
- Group은 공유 단위가 아니며 소속으로 열람 권한을 얻지 않는다. 이번 데이터와 벡터는 trusted internal 전용이다.
- Topic 크기와 설명 품질 가정이 깨지면 Group 품질·실제 Memory 조회 비용이 악화될 수 있다.
- 모델 교체 재구성, 대규모 성능 최적화, DB migration·운영 데이터 초기화·배포.
- 사용자 학습 과제인 Memory DB 조회 최적화는 건드리지 않는다.

## 9. 참고와 변경 범위

- [xMemory v4 §3.1, Appendix A.2·B](https://arxiv.org/html/2602.02007v4): centroid 배치, 국소 분할, 조직 점수와 실험 임계값 참고.
- [공개 memory_hierarchy.py](https://github.com/HU-xiaobai/xMemory/blob/main/src/core/memory_hierarchy.py): 요약 생성과 벡터 기반 조직 판단의 분리, fallback 구현 참고.

기존 조사에서 제외했던 국소 군집화는 이번 Group 분할 범위에서 채택한다. 전체 알고리즘의
그대로인 재현이나 같은 품질 보장을 뜻하지 않는다. Topic을 component로 사용하고, Group LM 요약·
병합·검색을 제외하며, fallback과 경계값 처리는 위 기본안을 따른다.

계획 작성 당시에는 애플리케이션 코드를 변경하지 않았다. 아래 구현에서도 실제 운영 데이터와
서비스를 변경하거나 배포하지 않았다.

## 10. 구현 결과와 검증 (2026-10-19)

- Domain에 Group·partition·정규화 벡터, 배치 정책, 분할 후보 생성, 조직 점수, 분할 선택과
  `GroupOrganizationPlanner`를 추가했다. 순수 정책은 framework·application·adapter에 의존하지 않는다.
- Application에 6개 output 역할과 내부 input port를 추가했다. batch 실행, 한 Topic 실행,
  임베딩 준비를 분리했다. 충돌은 최신 snapshot에서 최대 3회 판단하며 모델·규약·차원 불일치는
  `RebuildRequired`로 중단한다. 개별 실패는 결과에 남기고 다음 Topic을 처리한다.
- 기존 TextEmbedder를 Topic 설명용 adapter로 연결했다. 규약은 `passage: `, 벡터 재사용 key는
  Topic ID·설명 SHA-256·모델·입력 규약이다. 같은 key의 벡터는 불변이며 경쟁 생성 시 먼저 저장한 값을 사용한다.
- 새 SQLite 테이블은 `topic_embeddings`, `topic_groups`, `topic_group_memberships`,
  `topic_group_receipts`다. centroid는 동일 가중 평균의 정규화된 방향을 저장한다.
- Group reader/writer adapter는 원자 저장, 단일 소속, revision 확인, exact partition 검증,
  같은 요청 receipt 재사용과 `N→1` 저장을 지원한다. 병합 판단 정책은 구현하지 않았다.
- `ApplicationServices.organizeTopics.execute(limit)`로 명시적 내부 호출이 가능하다.
  `ApplicationServicesFactory.create(..., groupSettings=...)`로 정책 값을 조정한다.
  Topic 자동 생성, worker·HTTP·UI, Topic 자체 분할, 검색·entropy 확장은 연결하지 않았다.
- 신규 use-case/port README에 책임, 호출 순서, 실패·복구, 내부 신뢰 경계를 기록했다.

검증 결과:

- 신규 집중 테스트 25개 통과: Domain 9, Application 7, Group DB 8, embedding adapter 1.
- 앱 lifecycle 테스트 2개에서 시작 시 Group 작업을 자동 실행하지 않는 것을 함께 확인했다.
- `gradlew test` 통과: JVM 200개, 실패·오류·skip 0개. 배포 runtime 제어 스크립트 10개 통과.
- 임시 DB에서 생성→배치→분할→재연결→재호출, 동시 배치의 단일 승자, stale revision,
  receipt 재사용, 요청 key 충돌, 저장 도중 강제 실패 롤백, 후속 병합 저장 계약을 검증했다.
- 공유 Memory를 가진 Topic들의 내용·소속·관계와 Memory·Source·ACL 보존을 검증했다.
- 수치 fixture로 균형 점수, 응집도·기하 점수, 동일 벡터에서의 강제 분할·결정성·종료를 검증했다.
  수치 점수가 실제 검색 품질을 보장한다는 뜻은 아니다. 한국어 실데이터·실제 임베딩 품질 평가는 남아 있다.

운영·품질 한계는 8절 및 [use-case README](../../../application/src/main/kotlin/com/homeassistant/application/usecase/group/README.md)에 유지한다.
