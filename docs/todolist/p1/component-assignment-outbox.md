# Component 할당 outbox와 worker

- 상태: VERIFY — 실제 서버에서 Episode 저장 후 Component·Group 생성 확인 남음
- 2026-10-20: LM 할당과 `ComponentAssignmentGenerator`·`CompletionClient.isAvailable` readiness는 [Component 중심 벡터·ACL slice](component-centroid-acl-search.md)로
  대체되었다. 할당 readiness는 Episode 임베딩만 확인한다.
- 우선순위: P1
- 선행 작업: Episode → Component → Group 정리 (2026-10-19)

## 문제

`organizeEpisodes`는 앱에 조립만 되어 있고 호출하는 곳이 없어 Component·Group이 생성되지 않는다.
Episode 생성 직후 직접 호출하면 다음 문제가 생긴다.

- 실패한 Episode는 `Failed`만 반환되고 기록이 남지 않는다. 다음 Episode 생성 전까지 재시도되지 않는다.
- Ollama·backend CLI가 없어도 남은 Episode 전부에 대해 호출한다.
- 할당이 Episode마다 LM을 호출하므로 HTTP 응답이 그만큼 늦어진다.

## 목표

- Episode 저장 transaction에서 Component 할당 outbox 행을 함께 기록한다.
- app worker가 주기적으로 use case의 `isReady()`를 확인하고, 준비됐을 때만 outbox를 claim해 처리한다.
  readiness는 use case가 output port로 제공하며 app은 임베딩·backend를 직접 확인하지 않는다.
  준비되지 않은 회차는 claim하지 않으므로 시도 횟수가 늘지 않는다.
- 실패는 `markFailed`로 시도 횟수·오류를 기록하고 고정 지연 후 다시 claim한다. 최대 시도를 넘으면 그 Episode만 멈춘다.
- Episode 추출·저장 HTTP 흐름과 응답 형식은 바꾸지 않는다.

## AS-IS / TO-BE

AS-IS(생성 직후 직접 호출 가정)와 TO-BE의 sequence diagram은 2026-10-20 프로젝트 스레드에서 합의했다.

```text
AS-IS: Analysis → commit(episode + 색인 outbox) → OrganizePendingEpisodes.execute(limit)
       → pending(limit) = Component 소속 없는 Episode → 실패 시 Failed만 반환
TO-BE: Analysis → commit(episode + 색인 outbox + 할당 outbox) → 응답
       할당 Worker(주기) → readiness 확인 → OrganizePendingEpisodes.execute(limit)
       → claimReady → AssignEpisodeToComponents → markCompleted / markFailed → Group 정리
```

## 범위와 설계

| 위치 | 변경 |
|---|---|
| application `port/output/component/ComponentAssignmentOutbox` | claim·완료·실패·미등록 Episode 등록 계약. `PendingEpisodeSource` 대체 |
| application `OrganizePendingEpisodesService` | `pending(limit)` 대신 outbox claim, 결과에 따라 완료·실패 기록 |
| application input `OrganizePendingEpisodes` | `isReady()`: 할당이 호출하는 서비스가 준비됐는지. `enqueueUnassigned()`: outbox 도입 전 Episode 백필 |
| application `EpisodeEmbeddingGenerator`·`ComponentAssignmentGenerator` | `isAvailable()`: 각 port가 자기 서비스의 가용 여부를 보고. adapter-outbound가 Ollama runtime과 `CompletionClient`로 구현 |
| adapter-outbound `component_assignment_outbox` 테이블·repository | 색인 outbox와 같은 상태 모델(PENDING/PROCESSING/COMPLETED/FAILED) + 최대 시도 |
| adapter-outbound `EpisodeRepository.commit` | 같은 transaction에 할당 outbox 행 추가. 색인 outbox도 같은 `enqueue` 방식으로 정리 |
| integration-llm `CompletionClient.isAvailable` | 각 backend 구현이 CLI `--version` probe. 로그인은 확인하지 않음 |
| app `ComponentAssignmentWorker` | 시작 시 백필 1회, 이후 주기마다 `isReady()`가 참일 때만 `execute()`. 외부 시스템을 직접 알지 않음 |

- 색인 outbox 테이블(`indexing_outbox`)을 재사용하지 않는다. 이름이 색인 전용이고 최대 시도 정책이 다르다.
- Component 저장과 outbox 완료는 별도 transaction이다. 그 사이 중단되면 다음 claim이 Component 소속이 이미 있는 Episode를
  할당하지 않고 완료 처리한다. 같은 Episode가 두 번 할당되지 않는다.
- Group 정리는 별도 outbox 없이 같은 회차의 다음 단계로 실행한다. 미배치 Component는 상태로 판단하며 receipt로 멱등하다.
- 기본값: 주기 30초, batch 20, 재시도 지연 60초, 처리 lease 10분, 최대 시도 5회.

## 구현 순서

커밋 단위로 나누며 각 커밋은 단독으로 빌드된다.

1. 이 계획 문서
2. 할당 outbox port·테이블·repository, Episode 저장 시 enqueue와 임시 SQLite 테스트
3. `OrganizePendingEpisodesService`의 outbox 소비 전환, `PendingEpisodeSource` 제거
4. readiness: `CompletionClient.isAvailable`, readiness port·adapter, `isReady()`
5. app worker·조립·lifecycle 테스트
6. README와 구현 결과 기록

## 완료 조건

- Episode 저장 시 할당 outbox 행이 같은 transaction에 생긴다.
- 준비되지 않은 회차에는 claim이 일어나지 않는다.
- 실패한 Episode는 시도 횟수·오류가 남고 재시도되며, 최대 시도 후에는 다른 Episode 처리를 막지 않는다.
- 기존 DB의 미할당 Episode가 worker 시작 시 outbox에 등록된다.
- `./gradlew test`가 통과한다.

## 제외 범위

- Episode 추출·첨부 해석의 비동기화([별도 후보](../p2/episode-analysis-outbox-pipeline.md))
- backend CLI 로그인 상태 확인
- 저장 직후 worker를 즉시 깨우는 신호(주기 polling으로 대신)
- Component·Group 병합, 검색 연결

## 구현 결과 (2026-10-20)

- `component_assignment_outbox` 테이블과 `ComponentAssignmentOutboxRepository`를 추가했다. `EpisodeRepository.commit`이
  색인 outbox와 같은 transaction에서 할당 outbox 행을 등록한다(이미 있으면 유지). 두 outbox 모두 각 저장소의
  `enqueue`로 행을 추가하고, `EpisodeRepository`는 outbox 테이블을 직접 다루지 않는다.
- `PendingEpisodeSource`와 `ComponentRepository.pending`을 제거하고 `OrganizePendingEpisodesService`가 outbox를 claim한다.
  성공은 `markCompleted`, 실패는 `markFailed`(오류 2,000자까지)로 기록한다.
- claim 시점에 이미 Component 소속이 있는 Episode는 배정 없이 완료한다. Component 저장과 완료 기록은 별도 transaction이다.
- 준비 여부는 별도 port 없이 기존 출력 port가 보고한다. `EpisodeEmbeddingGenerator.isAvailable()`(Ollama runtime)과
  `ComponentAssignmentGenerator.isAvailable()`(`CompletionClient.isAvailable()`, CLI 실행 확인)을
  `AssignEpisodeToComponents.isReady()`가 묶고, `OrganizePendingEpisodes.isReady()`로 노출한다.
- app에 `EpisodeComponentAssignmentWorker`를 추가해 시작 시 백필(`enqueueUnassigned`) 1회, 이후 30초마다 `isReady()`가 참이면
  batch 20을 처리한다. readiness 확인을 포함한 모든 실패를 회차 안에서 잡아 다음 회차가 계속 실행된다.
- 사전 확인으로 놓치는 경우(확인 직후 장애, 로그인 안 된 CLI)는 [외부 서비스 장애 처리 표준화](../p2/external-service-failure-handling.md)로 남겼다.
- 검증: Linux 사본에서 `./gradlew test --continue` 실행, 235개 중 231개 통과. 실패 4개는 `powershell.exe`가 필요한
  `OllamaServerRuntimeTest`·`QdrantServerRuntimeTest`로 Linux 환경 제약이다. Windows 전체 테스트와 실제 서버 확인은 남아 있다.
