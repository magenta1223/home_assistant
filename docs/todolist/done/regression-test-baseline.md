# 최소 회귀 테스트 기준선

- 상태: DONE
- 목표: 기능 흐름이 안정화되는 시점
- 완료일: 2026-08-20

## 배경

현재 개발 속도를 위해 대부분의 테스트를 의도적으로 제거한 상태다. 구조가 계속 변하는 동안 광범위한 테스트를 먼저 복원하지 않는다. 다만 주말 사용을 막는 데이터 손실과 권한 오류는 최소 테스트로 고정할 필요가 있다.

## 계획

1. leaf memory 검색과 limit 적용을 검증한다.
2. Codex 분석 실패 후 같은 import가 재시도되는지 검증한다.
3. RESTRICTED memory가 허용되지 않은 사용자에게 노출되지 않는지 검증한다.
4. batch attach가 cycle과 부분 저장을 막는 기존 테스트를 유지한다.
5. Kakao parser 대표 fixture만 추가한다.
6. LLM 문장 품질은 단위 테스트 대신 고정 입력 evaluation으로 분리한다.

## 완료 조건

- 데이터 손실, privacy 누출, 검색 결과 소실을 재현하는 테스트가 존재한다.
- 개발 중인 내부 구조를 과도하게 고정하지 않는다.

## 완료 내용

- leaf memory 검색과 요청 limit 적용은 `MemorySearcherTest`로 고정했다.
- 분석기 실패 후 같은 import 재시도는 `MemoryAnalysisRetryTest`로 고정했다.
- RESTRICTED memory의 허용 사용자와 비허용 사용자 경계는 `MemoryRepositoryTest`와
  `MemoryAccessTest`로 고정했다.
- batch 저장 실패 rollback과 placement cycle/부분 업데이트 방지는
  `CanonicalMemoryBatchRepositoryTest`, `MemoryPlacementServiceTest`, `MemoryRepositoryTest`로 고정했다.
- Kakao 대표 export 형식은 세 fixture와 `KakaoExportParserTest`로 고정했다.
- LLM 문장 품질은 결정적 단위 테스트 범위에 넣지 않고 extractor의 구조·evidence 경계만 검증한다.

## 사용자에게 보이는 변화

직접적인 UI 변화는 없다. 데이터 손실, 권한 누출, 검색 결과 소실을 일으킨 핵심 회귀가 자동 테스트에서
탐지된다.

## 검증

- 2026-08-20: `:domain:test`, `:application:test`, `:adapter-inbound:test`,
  `:adapter-outbound:test` 통과

## 남은 제약

- 실제 모델 응답의 문장 품질과 대규모 데이터 성능은 이 기준선의 범위가 아니다.
