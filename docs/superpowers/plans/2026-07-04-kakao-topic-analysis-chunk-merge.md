# Kakao Topic Analysis Chunk+Merge Plan

## Summary

카카오 대화 전체를 한 번에 LLM에 보내는 현재 구조를 chunk 분석 → 후보 병합 분석 → 기존 검증/저장 구조로 바꾼다. 목적은 긴 대화에서 maxOutputToken 때문에 JSON이 중간에 잘리는 문제를 줄이면서, 기존 요구사항인 “시간순 chunk 기준으로 주제를 나누지 않고 A-B-A 주제를 병합”을 유지하는 것이다.

기본값은 다음으로 고정한다:

- chunk size: 200 records
- chunk별 후보 최대: 5 topics
- 최종 후보 최대: 20 topics
- topic당 evidence 최대: 5
- topic당 claims 최대: 3
- 기본 LLM max tokens: 8192

## Key Changes

- LlmTopicAnalyzer는 외부 인터페이스를 유지한다: analyze(SourceDocument): TopicAnalysisResult.
- 내부 동작을 다음처럼 변경한다:
    - records가 없으면 기존처럼 empty result.
    - records가 200개 이하면 기존 단일 분석 흐름을 사용하되, 프롬프트에 후보/근거/claim 개수 제한을 추가한다.
    - records가 201개 이상이면 records.chunked(200)으로 chunk별 분석을 수행한다.
    - chunk별 결과는 ValidatedTopic 목록으로 검증한 뒤, 전체 후보를 merge prompt에 넣어 최종 topic을 다시 생성한다.

- merge 단계는 원문 전체가 아니라 chunk 후보 요약을 입력으로 받는다.
    - 각 후보에는 title, summary, memoryTypes, domains, evidenceRecordIds, claims를 포함한다.
    - merge LLM 응답은 기존 TopicAnalysisOutputContract JSON schema를 재사용한다.
    - merge 응답의 evidenceRecordIds는 반드시 원본 r1, r2 같은 source record id여야 한다.

- 프롬프트를 두 종류로 분리한다:
    - chunk prompt: “이 chunk 안에서만 후보를 뽑고, 최대 5개로 제한”
    - merge prompt: “같은 주제가 시간상 떨어져 있어도 병합하고, 최종 최대 20개로 제한”

- AppConfig.DEFAULT_LLM_MAX_TOKENS를 2048에서 8192로 올린다.
    - chunking이 주된 해결책이고, token 증가는 merge JSON truncation 방지용 보조책이다.

## Implementation Details

- nlp/src/main/kotlin/com/homeassistant/nlp/topicanalysis/impl/LlmTopicAnalyzer.kt
    - analyzeValidTopics(document)를 orchestration 함수로 바꾼다.
    - 내부 helper를 추가한다:
        - analyzeChunk(document: SourceDocument): List<ValidatedTopic>
        - mergeTopics(document: SourceDocument, chunkTopics: List<ValidatedTopic>): List<ValidatedTopic>
        - chunkDocument(document: SourceDocument): List<SourceDocument>
        - renderTopicCandidates(topics: List<ValidatedTopic>): String

    - 기존 parseDomains, parseEvidence, parseClaims, renderDocument 검증 로직은 재사용한다.

- TopicAnalysisPrompt
    - 기존 system()은 단일/chunk 분석용으로 유지하되 개수 제한 문구를 추가한다.
    - mergeSystem()을 추가해 후보 병합 전용 지시를 제공한다.

- TopicAnalysisOutputContract
    - DTO/schema는 그대로 유지한다.
    - 잘린 JSON은 복구하지 않는다. 잘린 응답은 실패시키고, chunk+merge로 발생 가능성을 낮춘다.

## Test Plan

- LlmTopicAnalyzerTest
    - 201개 이상 record 입력 시 backend가 chunk별 2회 이상 호출되고 merge 호출이 추가되는지 검증한다.
    - chunk prompt에 최대 5개, topic당 evidence/claim 제한이 포함되는지 검증한다.
    - merge prompt에 “시간상 떨어진 같은 주제 병합”, “최종 최대 20개”가 포함되는지 검증한다.
    - chunk 2개에서 같은 주제가 나온 뒤 merge 응답 1개만 반환하면 최종 result도 1개인지 검증한다.
    - merge 응답이 존재하지 않는 evidenceRecordIds를 반환하면 기존처럼 TopicAnalysisException이 나는지 검증한다.
    - 200개 이하 입력은 merge 없이 단일 분석 호출만 수행하는지 검증한다.

- OpenRouterModelsTest
    - 기본 max token 기대값을 8192로 변경한다.

- 전체 검증:
    - .\gradlew.bat :nlp:test --tests *LlmTopicAnalyzerTest --tests *OpenRouterModelsTest
    - .\gradlew.bat build

## Assumptions

- Slack 승인 UI, preview 저장, selected save 흐름은 변경하지 않는다.
- chunking은 NLP 내부 구현 세부사항이며 API 응답 schema는 변경하지 않는다.
- v1에서는 chunk size와 후보 개수 제한을 환경변수로 노출하지 않는다.
- LLM 응답이 잘린 JSON일 때 부분 복구는 하지 않는다. 잘못 저장하는 것보다 실패시키는 쪽이 안전하다.