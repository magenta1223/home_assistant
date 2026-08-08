# Use case별 application 예외 계약

- 상태: DONE
- 목표: application 실패가 호출한 use case의 공개 계약으로만 노출되도록 제한

## 문제

`MemorySearchUnavailableException` 같은 application 예외의 생성자가 공개되어 있어 inbound나 outbound adapter도 같은 예외를 직접 만들 수 있었다. 또한 extraction과 placement의 구현 세부 실패 타입이 output port에 선언되어, 호출자가 어떤 use case가 실패했는지보다 내부 collaborator가 무엇인지 알아야 했다.

## 결정

- application 예외는 해당 `port/input`에 use case별 실패 계약으로 선언한다.
- 예외 constructor는 `internal`로 제한해 application 모듈의 use case 구현만 생성할 수 있게 한다.
- use case는 output port와 내부 collaborator의 실패를 자신의 예외로 변환한다.
- output port와 adapter는 application 예외를 생성하지 않고 일반 구현 실패를 전달한다.
- 상위 use case는 하위 use case 실패를 그대로 노출하지 않고 자신의 실패 계약으로 변환한다.

## 완료 내용

- search, answer, analysis, placement에 각각 독립적인 실패 계약을 배치했다.
- `MemorySearchUnavailableException`이 answer HTTP 경계로 직접 새지 않고 `MemoryAnswerUnavailableException`으로 변환되도록 했다.
- analysis의 source 저장, extraction, memory 저장, 상태 갱신 실패를 `MemoryAnalysisUnavailableException`으로 통일했다.
- placement extractor와 tree 저장 실패를 `MemoryPlacementException`으로 변환했다.
- output port의 `MemoryExtractionException`, `MemoryPlacementException` 선언을 제거했다.
- HTTP adapter는 answer/analysis use case의 unavailable 실패를 503으로 변환한다.
- application 예외가 input port 밖에 선언되거나 공개 constructor를 갖는 경우 실패하는 구조 테스트를 추가했다.
- cancellation은 unavailable 예외로 감싸지 않고 그대로 전파한다.

## 검증

- 각 use case의 실패 변환과 원인 보존 회귀 테스트
- answer HTTP 503 매핑 회귀 테스트
- application package 구조 테스트
- 전체 Gradle build
