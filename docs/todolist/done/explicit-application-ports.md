# Application input/output port 명시화

- 상태: DONE
- 목표: Application 경계 정리 시점

## 문제

현재 application의 use case 구현, inbound에서 호출하는 계약, outbound adapter가 구현하는 계약이 기능별 package에 함께 배치되어 있다. 기능 응집도는 높지만 어떤 interface가 application의 진입점이고 어떤 interface가 외부 기능을 요구하는지 package만으로 구분하기 어렵다.

## 결정

application을 `port/input`, `port/output`, `usecase`로 나누어 의존 방향을 명시한다.

- `port/input`: inbound adapter가 호출하는 use case 계약과 요청·결과 모델
- `port/output`: application이 요구하고 outbound adapter가 구현하는 계약
- `usecase`: input port를 구현하고 output port를 사용하는 기술 독립적인 orchestration

port는 application 관점의 능력을 표현한다. Qdrant, SQLite, Codex 등 구체 기술 이름은 port에 포함하지 않고 adapter 구현체에만 사용한다.

## 계획

1. 현재 application interface와 구현체를 input port, output port, use case 구현으로 분류한다.
2. `application/port/input`과 `application/port/output`을 기능 영역별 하위 package로 구성한다.
3. `MemorySearcher`, `MemoryAnalysisService`, Slack conversation orchestration 등 use case 구현을 `application/usecase`로 이동한다.
4. inbound adapter는 input port에만 의존하도록 변경한다.
5. outbound adapter는 output port를 구현하도록 import와 composition root wiring을 정리한다.
6. 요청·결과 모델은 해당 input port 가까이에 두고 domain model은 domain에 유지한다.
7. package 구조와 일치하도록 `AGENTS.md`의 application 구조 및 배치 원칙을 갱신한다.
8. module dependency와 기존 테스트를 실행해 동작 변화가 없는 구조 리팩터링인지 검증한다.

## 완료 조건

- application의 진입 계약, 외부 요구 계약, use case 구현 위치가 package만으로 구분된다.
- inbound adapter가 application use case 구현체에 직접 의존하지 않는다.
- output port에 구체적인 저장소, 벡터 DB, LLM provider 기술이 노출되지 않는다.
- adapter와 domain 사이의 기존 의존 방향 및 동작이 유지된다.
- 문서화된 module/package 구조가 실제 코드와 일치한다.

## 완료 내용

- application 코드를 기능별 `port/input`, `port/output`, `usecase` package로 재배치했다.
- memory analysis, answer, search, placement와 Slack conversation에 명시적인 input port를 두고 use case 구현체가 이를 구현하도록 했다.
- persistence, semantic search, extraction, placement, conversation session 계약을 output port로 분리했다.
- inbound runtime의 use case 조립을 composition root로 이동해 adapter가 use case 구현 package를 import하지 않도록 했다.
- output port의 `Codex` 명칭을 기술 중립적인 conversation session/turn 용어로 바꾸고 구체 기술명은 adapter에만 남겼다.
- package-directory 일치, adapter의 use case 구현 import 금지, output port의 구체 기술명 금지를 회귀 테스트로 고정했다.
- 전체 Gradle build와 기존 회귀 테스트를 통과해 동작 변화가 없는 구조 리팩터링임을 확인했다.
