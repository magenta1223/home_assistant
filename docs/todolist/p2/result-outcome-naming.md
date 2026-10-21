# Result·Outcome 이름 규칙 정리

- 상태: TODO
- 우선순위: P2

## 문제

`XxResult`와 `XxOutcome`의 구분이 일관되지 않아 이름만으로 타입의 모양을 알 수 없다.

- Outcome: 항목 하나의 결말을 나타내는 sealed 타입이다. `MemoryAssignmentOutcome`, `TopicOrganizationOutcome`,
  `GroupRefreshOutcome`, `GroupMergeOutcome`
- Result는 세 가지 모양이 섞여 있다.
  - ID와 Outcome의 짝: `MemoryAssignmentResult`, `TopicOrganizationResult`
  - 호출 하나의 묶음: `MemoryOrganizationResult`, `MemoryAnalysisResult`, `SearchMemoriesResult`, `SourceRecordSaveResult`,
    `MemoryIndexingRunResult`
  - Outcome과 같은 의미의 sealed 변형: `GroupOrganizationResult`, `GroupWriteResult`, `TopicDescriptionResult`,
    `ConversationTurnResult`, `MemoryConversationResult`, `KakaoChatSyncResult`

## 목표

- 규칙 하나를 정해 문서(코드 관례)에 남기고, 그에 맞게 이름을 바꾼다.
- 바꿀 이름은 port 계약을 포함하므로 inbound·outbound adapter와 테스트까지 함께 바꾼다. 동작은 바뀌지 않는다.

## 완료 조건

- 위 타입이 정한 규칙을 따른다. 전체 테스트 통과.
