# Episode 분석 파이프라인 outbox 전환

- 상태: DONE (2026-11-08)
- 우선순위: P2
- 선행 작업: [Component 할당 outbox와 worker](../p1/component-assignment-outbox.md)

## 문제

지식 주입 HTTP 요청 안에서 첨부 해석(LM)과 Episode 추출(LM)을 동기로 수행한다. LM 지연이 응답 지연이 되고,
backend 장애 시 요청이 `503`으로 실패해 사용자가 다시 보내야 한다.

## 목표

외부 호출 단계를 commit 이후 outbox로 옮겨 Source 저장 → 추출 → Episode 저장 → 색인·할당 → Group의 파이프라인으로 만든다.

## 검토할 것

- 첨부 해석: 지금은 Source 저장 전에 해석한다(`EpisodeAnalysisService`). 원본 blob(`source_references`)을 먼저 저장하고 worker가 해석하도록 순서를 바꾼다.
- Episode 추출: `source_records.analysis_status`가 사실상 outbox다. 시도 횟수·오류·backoff 열을 추가한다.
- UI 계약: 추출 결과 건수·해석 불가 오류를 동기 응답 대신 "접수됨" + 처리 상태 조회로 바꾼다. 권한·열람자·중복 검증은 동기로 거절한다.
- 대화는 동기 요청이므로 대상이 아니다.

## Release note

- 완료일: 2026-11-08
- `POST /api/knowledge/import/analyze`는 권한·열람자·접근 범위 충돌·전체 중복(`400`·`401`·`403`·`409`)을 동기로
  거절하고, source record와 reference 원본을 해석 없이 저장한 뒤 분석 job 하나를 `episode_analysis_jobs`에 등록해
  `202`와 `jobId`, 저장 건수(`importedRecordCount`·`retriedRecordCount`·`alreadyAnalyzedRecordCount`)와 열람 범위를
  돌려준다. 저장소 장애는 `503`이다. reference가 있으면 해석 전이라 전체 중복 `409`를 내지 않는다.
- `EpisodeAnalysisWorker`가 한 스레드에서 준비된 job이 없을 때까지 하나씩 처리하고, 없으면 10초 뒤 다시 확인한다.
  job마다 reference를 해석해 `reference:<sha256>:<segment>` 키의 record로 저장하고, 아직 분석되지 않은 record에서
  Episode를 추출·저장한 뒤 episode 수를 기록한다.
- 재시도·lease는 Component 할당 outbox와 같다. 실패한 job은 60초 뒤부터 다시 claim되고 시도가 5회에 이르면
  `FAILED`로 남는다. 10분 안에 끝나지 않은 처리는 다른 round가 다시 claim하며 이전 시도의 기록은 무시된다.
- `GET /api/knowledge/import/jobs/{jobId}`는 job을 제출한 사용자에게만 `status`·`episodeCount`·`lastError`를
  돌려준다. 다른 사용자의 job과 없는 job은 `404`, 정수가 아닌 id는 `400`이다.
- 사용자에게 보이는 변화: 지식 주입 화면은 "접수됨"과 저장 건수를 먼저 보여 주고 3초마다 상태를 조회해 완료 시
  episode 수를, 실패 시 마지막 오류를 보여 준다. 해석할 수 없는 PDF·이미지는 즉시 `400`이 아니라 job의
  마지막 오류로 나타난다. 응답의 `episodes` 목록·`sourceType`·`sourceName`은 없어졌다.
  카카오 exporter는 `202`를 성공(`accepted`)으로 본다.
- 검증: `EpisodeAnalysisJobQueueRepositoryTest`, `SourceRecordRepositoryTest`, 접수와 분석 round를 실제 SQLite로
  함께 검증하는 `EpisodeAnalysisRetryTest`, `KnowledgeInjectionRoutesTest`, `EpisodeAnalysisWorkerTest`,
  exporter `test_knowledge_import`, 전체 `gradlew build`(260개) 통과. 지식 주입 화면의 접수·상태 조회는
  mock 서버로 완료·실패 흐름을 확인했다.

## 남은 제약

- record 저장과 job 등록은 별도 호출이다. 등록 전에 실패하면 record가 `PENDING`으로 남고, 같은 source를 다시
  제출해야 새 job에 담긴다.
- 처리 도중 중단된 job이 lease 만료 후 다시 claim되면 남은 `PENDING` record가 없어 episode 수가 0으로 기록될 수 있다.
- 빈 해석 결과와 해석 record의 열람 범위 충돌은 해석 모델 호출 뒤에 드러나므로 시도마다 호출이 반복된다.
- 한 번에 한 job만 분석하지만 Component 할당 worker와 대화는 다른 스레드에서 모델을 동시에 호출한다.
- 지식 주입 화면은 `FAILED`에서 조회를 멈춘다. 서버가 재시도해 완료한 결과는 다시 제출하거나 상태를 직접 조회해야 보인다.
