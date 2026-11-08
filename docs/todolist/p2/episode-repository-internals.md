# EpisodeRepository 내부 정리

- 상태: TODO
- 우선순위: P2
- 선행 작업: 없음

## 문제

`EpisodeRepositoryImpl`의 동작은 맞지만, 내부 구조가 다른 Repository 규칙과 맞지 않는다.

- 행 변환 공유: `ResultRow.toEpisode()`가 `internal` 확장 함수로 열려 있다. `ComponentAssignmentOutboxRepository`가 `with(EpisodeRepositoryImpl(db)) { row.toEpisode() }`로 Repository 인스턴스를 만들어 빌려 쓴다. 행 변환이 Repository 하나에 묶여 있고, 다른 adapter가 그 내부에 기댄다.
- orchestration 형태가 아님: `save(batch)` 한 함수에 idempotency 조회, ACL 교집합 계산, Episode·evidence·viewer insert, outbox 등록, source record 상태 변경이 섞여 있다. 이름과 반환값만으로 흐름을 읽을 수 없다(SLAP).
- `EpisodeBatch.analyzedSourceRecordIds` 의미 불명확: "이번 분석에 넣은 record"인지 "분석 완료로 표시할 record"인지 이름으로 드러나지 않는다. 실제로는 `ANALYZED` 상태 변경 대상이다.
- 이미 있는 Episode에 대한 `ensureOutbox`: idempotency key로 기존 Episode를 찾은 경우에도 outbox를 다시 등록한다(`insertIgnore`라 중복은 없음). 재분석 시 할당 누락을 막으려는 것인지, 불필요한 호출인지 의도가 코드에 없다.

## 목표

- Episode 행 변환을 Repository 밖의 공용 mapper(persistence 패키지 내부)로 옮겨 두 adapter가 같은 변환을 쓰게 한다.
- `save`를 단계 이름(기존 Episode 찾기, 새 Episode 쓰기, outbox 등록, record 분석 완료 표시)으로 나눈다.
- `analyzedSourceRecordIds`를 의도가 드러나는 이름으로 바꾸거나 KDoc으로 의미를 고정한다.
- 기존 Episode의 outbox 재등록이 필요한 이유를 확인하고, 필요하면 KDoc에 남기고 아니면 제거한다.

## 완료 조건

- 구조 변경 전에 AS-IS/TO-BE 설계안과 sequence diagram으로 결정받는다.
- 저장 결과와 트랜잭션 원자성이 같다. adapter 테스트는 port 공개 동작으로 확인하고, 전체 테스트가 통과한다.
