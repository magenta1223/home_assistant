# Slack Kakao Subplan 4: Confirmation UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 분석된 topic을 Slack DM에서 보여주고 업로더가 저장할 topic을 modal에서 선택하게 한다.

**Architecture:** Block Kit 생성은 순수 mapper로 분리하고 Slack action handler는 authorization과 상태 확인만 담당한다. 실제 저장은 다음 subplan에서 구현할 command 포트로 위임한다.

**Tech Stack:** Slack Block Kit, Bolt actions/views, Kotlin

---

### Task 1: 분석 결과 메시지

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackTopicBlocks.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackTopicBlocksTest.kt`

- [ ] topic 제목, 요약, memory type과 총 topic 수를 표시하는 block 생성 테스트를 작성한다.
- [ ] message에 `검토` 버튼과 job ID를 action value로 넣는 테스트를 작성한다.
- [ ] Slack block text 제한을 넘는 제목과 요약을 안전하게 잘라내는 테스트를 작성한다.
- [ ] 분석 worker가 `AWAITING_CONFIRMATION` 전환 후 결과 메시지를 보내도록 연결한다.
- [ ] `./gradlew :app:test --tests '*SlackTopicBlocksTest'`를 실행한다.
- [ ] `git commit -m "feat: render Slack topic analysis previews"`로 커밋한다.

### Task 2: Topic 선택 modal

**Files:**
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackTopicBlocks.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackTopicBlocksTest.kt`

- [ ] `multi_static_select` option value가 preview topic index 문자열인지 테스트한다.
- [ ] 모든 topic이 initial options로 선택되는지 테스트한다.
- [ ] modal private metadata에 job ID만 저장하는지 테스트한다.
- [ ] topic이 100개를 초과하면 modal 대신 범위 축소 오류를 반환하는 테스트를 작성한다.
- [ ] option label과 description에 원문 메시지나 evidence 전문을 포함하지 않게 한다.
- [ ] `./gradlew :app:test --tests '*SlackTopicBlocksTest'`를 실행한다.
- [ ] `git commit -m "feat: build topic selection modal"`로 커밋한다.

### Task 3: Interactivity handler

**Files:**
- Create: `app/src/main/kotlin/com/homeassistant/app/slack/SlackConfirmationHandlers.kt`
- Modify: `app/src/main/kotlin/com/homeassistant/app/slack/SlackSocketRuntime.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/slack/SlackConfirmationHandlersTest.kt`

- [ ] `검토` 버튼을 원래 업로더가 누르면 modal이 열리는 테스트를 작성한다.
- [ ] 다른 사용자가 누르면 modal을 열지 않고 ephemeral 오류를 반환하는 테스트를 작성한다.
- [ ] job이 `AWAITING_CONFIRMATION`이 아니면 만료된 요청으로 처리하는 테스트를 작성한다.
- [ ] modal submit에서 선택 index 집합과 job ID를 command 포트로 전달하는 테스트를 작성한다.
- [ ] 빈 선택과 modal cancel은 취소 command로 전달한다.
- [ ] 모든 action/view payload를 3초 안에 ACK하고 후속 처리는 worker에서 실행한다.
- [ ] `./gradlew :app:test --tests '*SlackConfirmationHandlersTest'`를 실행한다.
- [ ] `./gradlew build`를 실행한다.
- [ ] `git commit -m "feat: handle Slack topic confirmation actions"`로 커밋한다.

## 완료 조건

- 업로더만 자신의 preview를 열고 선택할 수 있다.
- topic별 선택과 빈 선택 취소가 command로 전달된다.
- 이 단계까지는 modal submit이 영구 저장을 직접 수행하지 않는다.
- `./gradlew build`가 통과한다.

