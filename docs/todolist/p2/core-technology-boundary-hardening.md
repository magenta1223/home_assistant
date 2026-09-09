# Core 기술 경계 강화

- 상태: TODO
- 우선순위: P2
- 선행 작업: 없음

## 문제

현재 모듈 의존성은 `app -> adapter-inbound/adapter-outbound -> application -> domain` 방향을
지키고 있으며, Application과 Domain은 DB, Slack, Qdrant, Ollama, Codex 같은 구체 구현을 직접
참조하지 않는다. 다만 엄격한 Layered/Hexagonal Architecture 기준에서는 다음 기술 관심사가
Core 경계 안에 남아 있다.

1. Domain 모델이 `kotlinx.serialization`의 `@Serializable`, `@SerialName`과 Gradle plugin/API에
   의존한다.
2. Application input/result 모델이 `@Serializable`에 의존해 전송 형식과 use case 계약의 경계가
   섞여 있다.
3. Application뿐 아니라 `adapter-inbound`, `adapter-outbound`, `integration-codex` 같은 library
   모듈도 SLF4J API가 아니라 구체 logging binding인 `logback-classic`을 직접 의존한다.

현재 동작 오류는 아니지만 이 결합을 방치하면 다른 transport나 serialization 방식으로 교체할 때
Core 변경이 필요하고, architecture test도 이를 회귀로 감지하지 못한다.

## 목표

- Domain 모델에서 JSON/serialization 기술 의존을 제거한다.
- Application의 use case 계약을 transport serialization과 분리한다.
- Library 모듈은 logging API만 의존하고 실제 binding은 `app`에서 선택한다.
- 위 경계를 자동화된 architecture test로 고정한다.

## 구현 계획

1. `@Serializable`, `@SerialName`이 사용된 Domain/Application 모델과 실제 직렬화 호출 지점을
   목록화한다.
2. 외부 JSON schema가 필요한 Codex, HTTP, persistence adapter에 전용 DTO를 두고 Domain 및
   Application 모델과 양방향으로 변환한다.
3. Domain과 Application에서 Kotlin serialization Gradle plugin 및 API 의존을 제거하고 전체
   import 경계를 검증한다.
4. `application`, 두 adapter와 `integration-codex`의 `logback-classic` 의존을 `slf4j-api`로
   교체하고 실제 Logback binding은 `app` 모듈에만 둔다.
5. 다음 규칙을 architecture test에 추가한다.
   - Domain은 Kotlin/JDK와 허용된 domain dependency 외 프레임워크를 import하지 않는다.
   - Application은 adapter, configuration, concrete logging binding을 의존하지 않는다.
   - Application output port와 Domain 모델에는 구현 기술명 및 transport annotation이 없다.
7. HTTP, Slack, Codex 분석, persistence round-trip 회귀 테스트로 변환 전후 계약 호환성을 검증한다.

## 완료 조건

- `domain`과 `application`에서 `kotlinx.serialization` import 및 serialization Gradle plugin/API
  의존이 제거된다.
- `app`을 제외한 library 모듈에서 `logback-classic` 의존이 제거되고 실제 logging binding은
  composition/runtime 영역에만 존재한다.
- Application과 Domain이 Ktor, Slack, Exposed/SQLite, Qdrant, Ollama, Codex 구현 클래스를 참조하지
  않는다는 검사가 자동화된다.
- 기존 HTTP/Slack 요청·응답 형식, 저장 데이터, Codex structured output 호환성이 유지된다.
- 전체 테스트가 통과한다.

## 제외 범위

- 현재 port/use case 전체의 일괄적인 이름 변경
- DB schema 또는 memory domain 모델의 기능 변경
- 새로운 logging/observability 제품 도입
- MQTT 등 현재 존재하지 않는 외부 채널 추가
- frozen Slack callback refactor

## 현재 상태 (2026-09-08)

미구현이다. Domain과 Application 모델에 `kotlinx.serialization` annotation이 남아 있고,
`application`, `adapter-inbound`, `adapter-outbound`, `integration-codex`가 `logback-classic`을 직접
의존한다. logging binding 정리는 작은 독립 변경으로 먼저 수행할 수 있다. serialization DTO 분리는
호환성 회귀 범위가 크므로 transport 교체 필요가 생기기 전까지 P2를 유지한다.
