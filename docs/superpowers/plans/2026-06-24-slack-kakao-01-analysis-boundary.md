# Slack Kakao Subplan 1: Analysis Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slack과 무관하게 선택 topic 저장 및 preview 삭제가 가능한 topic-analysis API를 만든다.

**Architecture:** 기존 `KakaoMessageTopicAnalysisService`의 preview/save 흐름을 유지하되 save 요청에 topic index 집합을 추가한다. Preview 저장소에 삭제 연산을 추가해 승인과 취소 시 민감한 원문을 제거할 수 있게 한다.

**Tech Stack:** Kotlin, kotlinx.serialization, Exposed, SQLite, kotlin.test

---

### Task 1: 선택 topic 저장 계약

**Files:**
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/api/TopicAnalysisModels.kt`
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/api/TopicAnalysisUseCase.kt`
- Test: `app/src/test/kotlin/com/homeassistant/app/routes/KakaoImportRoutesTest.kt`

- [ ] `TopicAnalysisSaveRequest`에 `selectedTopicIndexes: Set<Int>`를 추가하는 실패 테스트를 작성한다.
- [ ] `TopicAnalysisUseCase.saveAnalysis(previewId, selectedTopicIndexes)` 시그니처를 정의한다.
- [ ] index가 비어 있거나 음수이거나 preview topic 범위를 벗어나면 요청을 거부하는 테스트를 작성한다.
- [ ] `./gradlew :app:test --tests '*KakaoImportRoutesTest'`를 실행해 새 테스트가 계약 미구현으로 실패하는지 확인한다.
- [ ] route가 선택 index를 use case로 전달하도록 최소 구현한다.
- [ ] 같은 명령을 다시 실행해 통과를 확인한다.
- [ ] `git commit -m "refactor: support selected topic save requests"`로 커밋한다.

### Task 2: Preview 삭제

**Files:**
- Modify: `domain/src/main/kotlin/com/homeassistant/domain/topicanalysis/TopicAnalysisPreviewStore.kt`
- Modify: `repository/src/main/kotlin/com/homeassistant/repository/repo/topicanalysis/TopicAnalysisPreviewRepository.kt`
- Test: `repository/src/test/kotlin/com/homeassistant/repository/kakao/TopicAnalysisPreviewRepositoryTest.kt`

- [ ] 존재하는 preview를 삭제하고 다시 조회하면 `null`인 테스트를 작성한다.
- [ ] 존재하지 않는 preview 삭제가 `false`를 반환하는 테스트를 작성한다.
- [ ] `TopicAnalysisPreviewStore.deletePreview(previewId): Boolean`을 정의한다.
- [ ] Exposed transaction 안에서 해당 preview row를 삭제하도록 구현한다.
- [ ] `./gradlew :repository:test --tests '*TopicAnalysisPreviewRepositoryTest'`를 실행한다.
- [ ] `git commit -m "feat: delete completed topic analysis previews"`로 커밋한다.

### Task 3: 선택 저장과 preview 수명주기

**Files:**
- Modify: `nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/impl/KakaoMessageTopicAnalysisService.kt`
- Test: `nlp/src/test/kotlin/com/homeassistant/nlp/topicanalysis/KakaoMessageTopicAnalysisServiceTest.kt`

- [ ] 세 topic preview에서 index `0`과 `2`만 저장되는 테스트를 작성한다.
- [ ] 저장 시 파일 전체 메시지가 import되고 선택 topic의 evidence만 저장 ID로 remap되는지 검증한다.
- [ ] 저장 성공 후 preview가 삭제되는 테스트를 작성한다.
- [ ] 잘못된 index에서는 메시지와 topic을 저장하지 않고 preview를 유지하는 테스트를 작성한다.
- [ ] 선택 topic만 필터링한 뒤 기존 import/remap/create 흐름을 실행하도록 구현한다.
- [ ] 저장 완료 후에만 preview를 삭제하도록 구현한다.
- [ ] `./gradlew :nlp:test :repository:test :app:test`를 실행한다.
- [ ] `git commit -m "feat: persist selected topic analysis results"`로 커밋한다.

## 완료 조건

- 기존 HTTP preview/save 흐름이 선택 topic 저장을 지원한다.
- 저장 성공 후 preview 원문이 남지 않는다.
- 잘못된 요청이나 저장 실패에서는 preview가 유지된다.
- `./gradlew build`가 통과한다.

