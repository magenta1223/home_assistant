# Slack Kakao Subplan 3: Validation and Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 다운로드한 TXT가 KakaoTalk export인지 판정하고, 유효한 파일만 topic analysis preview로 변환한다.

**Architecture:** Kakao 형식 판정은 `domain/kakao`에 두고 Slack을 알지 못하게 한다. Slack worker는 다운로드, UTF-8 decode, validation, 기존 `TopicAnalysisUseCase.analyze` 호출만 조정한다.

**Tech Stack:** Kotlin, existing Kakao parser, existing topic analysis use case

---

### Task 1: Kakao 문서 검증

**Files:**
- Create: `domain/src/main/kotlin/com/homeassistant/domain/kakao/KakaoExportValidator.kt`
- Test: `domain/src/test/kotlin/com/homeassistant/domain/kakao/KakaoExportValidatorTest.kt`

- [ ] 기존 두 Kakao 형식을 각각 valid로 판정하는 테스트를 작성한다.
- [ ] 일반 메모, 빈 파일, 헤더만 있는 파일, message regex가 한 건도 없는 파일을 invalid로 판정하는 테스트를 작성한다.
- [ ] UTF-8 BOM이 있는 파일을 valid로 판정하는 테스트를 작성한다.
- [ ] 판정은 `KakaoMessageParser.parse()` 결과가 한 건 이상이고 sender/time/content가 비어 있지 않은 조건으로 제한한다.
- [ ] `./gradlew :domain:test --tests '*KakaoExportValidatorTest'`를 실행한다.
- [ ] `git commit -m "feat: validate KakaoTalk text exports"`로 커밋한다.

### Task 2: 처리 job 저장소

**Files:**
- Create: `datamodel/src/main/kotlin/com/homeassistant/datamodel/slack/SlackImportJob.kt`
- Create: `domain/src/main/kotlin/com/homeassistant/domain/slack/SlackImportJobStore.kt`
- Create: `repository/src/main/kotlin/com/homeassistant/repository/db/tables/SlackImportJobTable.kt`
- Create: `repository/src/main/kotlin/com/homeassistant/repository/repo/slack/SlackImportJobRepository.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/db/DatabaseFactory.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/RepositoryFactory.kt`
- Test: `repository/src/test/kotlin/com/homeassistant/repository/slack/SlackImportJobRepositoryTest.kt`

- [ ] 상태 enum을 `PROCESSING`, `AWAITING_CONFIRMATION`, `SAVING`, `COMPLETED`, `CANCELLED`, `FAILED`로 정의한다.
- [ ] event ID와 file ID 조합이 유일한지 테스트한다.
- [ ] job 생성, 상태 전환, preview ID 연결, 조회 테스트를 작성한다.
- [ ] 재시작 복구를 위해 진행 중 job을 `FAILED`로 전환하는 repository 연산을 테스트한다.
- [ ] `./gradlew :repository:test --tests '*SlackImportJobRepositoryTest'`를 실행한다.
- [ ] `git commit -m "feat: persist Slack import job state"`로 커밋한다.

### Task 3: 분석 orchestration

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackKakaoAnalysisWorker.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackKakaoAnalysisWorkerTest.kt`

- [ ] 다운로드 실패 시 job이 `FAILED`가 되고 사용자 오류 메시지를 보내는 테스트를 작성한다.
- [ ] 비 UTF-8, 일반 TXT, 빈 Kakao export에서는 LLM을 호출하지 않는 테스트를 작성한다.
- [ ] 유효한 파일에서 `TopicAnalysisRequest(sourceType = "kakao", sourceName = filename, text = decodedText)`를 호출하는 테스트를 작성한다.
- [ ] topic이 없으면 preview를 삭제하고 job을 `COMPLETED`로 종료하는 테스트를 작성한다.
- [ ] topic이 있으면 preview ID를 job에 연결하고 `AWAITING_CONFIRMATION`으로 전환하는 테스트를 작성한다.
- [ ] 처리 시작과 실패 결과는 DM thread에 게시하도록 한다.
- [ ] `./gradlew :app:test --tests '*SlackKakaoAnalysisWorkerTest'`를 실행한다.
- [ ] `./gradlew build`를 실행한다.
- [ ] `git commit -m "feat: analyze Kakao files received from Slack"`로 커밋한다.

## 완료 조건

- 일반 TXT는 LLM 호출 전에 거부된다.
- 정상 Kakao TXT는 preview와 `AWAITING_CONFIRMATION` job을 생성한다.
- 같은 Slack 이벤트를 재수신해도 분석이 한 번만 실행된다.
- `./gradlew build`가 통과한다.

