# Slack Kakao Subplan 5: Save and Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slack 승인 command를 선택 topic 저장으로 연결하고 중복 클릭, 실패, 취소, preview 정리를 일관되게 처리한다.

**Architecture:** confirmation handler는 command 객체만 생성한다. `SlackTopicConfirmationService`가 job 상태 전환, topic save use case 호출, preview 정리, Slack 결과 메시지를 순서대로 조정한다.

**Tech Stack:** Kotlin coroutines/executor, Exposed, existing topic analysis repositories, Slack Web API

---

### Task 1: 승인 command 서비스

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackTopicConfirmationService.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackTopicConfirmationServiceTest.kt`

- [ ] `AWAITING_CONFIRMATION` job만 `SAVING`으로 전환되는 테스트를 작성한다.
- [ ] 선택 index를 `saveAnalysis(previewId, indexes)`에 전달하는 테스트를 작성한다.
- [ ] 저장 성공 시 job이 `COMPLETED`가 되고 저장 topic 수를 DM으로 알리는 테스트를 작성한다.
- [ ] 저장 실패 시 job이 `FAILED`가 되며 preview는 재시도를 위해 유지되는 테스트를 작성한다.
- [ ] 중복 submit은 save use case를 두 번 호출하지 않는 테스트를 작성한다.
- [ ] `./gradlew :app:test --tests '*SlackTopicConfirmationServiceTest'`를 실행한다.
- [ ] `git commit -m "feat: save confirmed Slack topics"`로 커밋한다.

### Task 2: 취소와 원문 삭제

**Files:**
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackTopicConfirmationService.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackTopicConfirmationServiceTest.kt`

- [ ] 취소 command가 preview를 삭제하고 job을 `CANCELLED`로 변경하는 테스트를 작성한다.
- [ ] 빈 선택 submit도 동일하게 처리하는 테스트를 작성한다.
- [ ] 이미 완료·취소된 job에 대한 반복 command가 no-op인지 테스트한다.
- [ ] 취소 결과와 원문 삭제 완료를 사용자에게 알리도록 구현한다.
- [ ] `./gradlew :app:test --tests '*SlackTopicConfirmationServiceTest'`를 실행한다.
- [ ] `git commit -m "feat: cancel Slack previews and remove raw text"`로 커밋한다.

### Task 3: 재시작 복구와 observability

**Files:**
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackSocketRuntime.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackSocketRuntimeTest.kt`

- [ ] 시작 시 `PROCESSING`과 `SAVING` job을 `FAILED`로 전환하는 테스트를 작성한다.
- [ ] 로그에는 event ID, file ID, job ID, 상태만 남고 토큰·원문·메시지 본문은 남지 않는지 점검한다.
- [ ] Slack 연결, 다운로드, validation, analysis, confirmation, save 단계별 성공·실패 로그를 추가한다.
- [ ] 실패한 job은 같은 파일을 새 이벤트로 다시 업로드할 수 있게 한다.
- [ ] `./gradlew test`와 `./gradlew build`를 실행한다.
- [ ] `git commit -m "feat: recover interrupted Slack import jobs"`로 커밋한다.

## 완료 조건

- 승인 시 전체 Kakao 메시지와 선택 topic만 저장된다.
- 승인·취소 성공 후 preview 원문이 삭제된다.
- 실패와 중복 클릭이 중복 저장을 만들지 않는다.
- 전체 테스트와 빌드가 통과한다.

