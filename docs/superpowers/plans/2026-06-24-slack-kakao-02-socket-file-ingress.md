# Slack Kakao Subplan 2: Socket File Ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slack Socket Mode에 연결하고 봇 DM의 TXT 첨부 이벤트를 안전하게 수신·다운로드한다.

**Architecture:** Slack SDK 객체 생성과 이벤트 등록을 `app/slack` 패키지에 격리한다. listener는 즉시 ACK한 뒤 immutable 작업 객체를 executor에 전달하며 이 단계에서는 Kakao 분석을 호출하지 않는다.

**Tech Stack:** Slack Bolt for Java 1.49.0, Tyrus WebSocket 1.20, Kotlin, MockK

---

### Task 1: 의존성과 설정

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `core/src/main/kotlin/com/homeassistant/core/constants/AppConfig.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackConfigTest.kt`

- [ ] Slack 설정이 `SLACK_APP_TOKEN`, `SLACK_BOT_TOKEN`, `SLACK_MAX_FILE_SIZE_BYTES`를 읽는 테스트를 작성한다.
- [ ] 기본 최대 크기를 `10_485_760` bytes로 고정한다.
- [ ] `bolt-socket-mode:1.49.0`, `javax.websocket-api:1.1`, `tyrus-standalone-client:1.20`을 version catalog에 추가한다.
- [ ] 누락된 토큰에서 시작 설정이 명확한 예외를 내는지 테스트한다.
- [ ] `./gradlew :app:test --tests '*SlackConfigTest'`를 실행한다.
- [ ] `git commit -m "build: add Slack Socket Mode dependencies"`로 커밋한다.

### Task 2: 파일 ingress 모델과 필터

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackFileIngress.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackFileIngressTest.kt`

- [ ] 이벤트 ID, file ID, user ID, DM channel ID, message timestamp를 담는 immutable 입력 모델을 정의한다.
- [ ] 봇 메시지, DM이 아닌 메시지, 파일 없는 메시지를 무시하는 테스트를 작성한다.
- [ ] 한 메시지의 여러 파일을 각각 ingress 작업으로 변환하는 테스트를 작성한다.
- [ ] `.txt`가 아닌 파일과 크기 초과 파일을 거부하는 테스트를 작성한다.
- [ ] `./gradlew :app:test --tests '*SlackFileIngressTest'`를 실행한다.
- [ ] `git commit -m "feat: filter Slack DM file events"`로 커밋한다.

### Task 3: 파일 다운로드 포트

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackFileClient.kt`
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackWebApiFileClient.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackWebApiFileClientTest.kt`

- [ ] `files.info` 결과에서 filename, size, private download URL을 읽는 테스트를 작성한다.
- [ ] Bot Token을 Authorization header로 사용해 byte array를 다운로드하는 테스트를 작성한다.
- [ ] 비정상 HTTP status, 빈 응답, 최대 크기 초과를 오류로 반환하는 테스트를 작성한다.
- [ ] 로그와 예외 메시지에 토큰과 파일 본문이 포함되지 않는지 검증한다.
- [ ] `./gradlew :app:test --tests '*SlackWebApiFileClientTest'`를 실행한다.
- [ ] `git commit -m "feat: download private Slack files"`로 커밋한다.

### Task 4: Socket Mode 수명주기

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackSocketRuntime.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/Application.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackSocketRuntimeTest.kt`

- [ ] listener가 envelope를 ACK한 뒤 worker에 작업을 제출하는 순서를 테스트한다.
- [ ] `SocketModeApp.startAsync()`가 Ktor 시작을 block하지 않는지 테스트한다.
- [ ] application stop hook에서 socket과 executor가 닫히는지 테스트한다.
- [ ] 중복 event ID가 같은 프로세스 안에서 두 번 worker로 전달되지 않게 한다.
- [ ] `./gradlew :app:test`와 `./gradlew build`를 실행한다.
- [ ] `git commit -m "feat: run Slack Socket Mode with Ktor"`로 커밋한다.

## 완료 조건

- 유효한 DM TXT 첨부가 다운로드 worker까지 전달된다.
- 이벤트 ACK가 다운로드나 분석 완료를 기다리지 않는다.
- 이 단계에서는 LLM과 DB 저장을 호출하지 않는다.
- `./gradlew build`가 통과한다.

