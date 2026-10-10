# Slack 기능 제거와 HTTP 단일 진입점

- 상태: DONE (2026-10-10)
- 요청일: 2026-10-10

## 범위와 순서

1. Slack adapter·런타임·명령·DM 등록/답변·지식 입력 workflow와 조립을 제거한다.
2. Slack 전용 등록 대기 질문 저장소와 legacy 설정 예약 경로를 제거한다.
3. Slack SDK·환경변수·manifest·업데이트 도구를 삭제한다.
4. HTTP가 사용하는 공통 UserId·ACL·Memory 분석·대화·요청 중복 방지·10분 idle lease를 보존한다.
5. HTTP 대화 저장소의 코드 이름을 중립적으로 바꾸되 기존 물리 테이블/컬럼 매핑은 유지한다.
   실제 DB를 삭제하거나 변환하지 않는다. HTTP도 이미 해당 테이블을 사용한다.
6. 현재 문서와 지침을 HTTP 기준으로 갱신하고 집중 테스트 후 전체 테스트를 실행한다.

Slack 외부 앱 삭제·토큰 폐기·운영 배포는 이 저장소 코드 제거 작업에 포함하지 않는다.
새 HTTP 기능이나 사용자 등록 UI는 이번에 추가하지 않는다.

## 완료 조건

- 실행 코드·의존성·앱 조립에 Slack 연결 기능이 없다.
- HTTP 인증·지식 입력·대화·세션·ACL 회귀 테스트와 공통 저장소 테스트가 통과한다.
- 사용자와 기존 저장 데이터는 보존한다.

## 실제 변경

- Slack Socket Mode·DM·slash command·modal·사용자 등록 안내·지식 주입과 관련 테스트를 삭제했다.
- Slack에서만 쓰던 KnowledgeInjectionWorkflowService, MemoryAnswerWorkflowService와 input port,
  등록 전 질문 대기 저장소·테이블 선언, 환경변수 기반 legacy user 예약을 제거했다.
- Slack SDK, WebSocket client 의존성, 환경변수 상수, manifest와 갱신 script/Gradle task를 제거했다.
- HTTP 공통 대화의 request key를 conversation port로 옮겼다. Slack 전송 완료 callback과
  delivery ID 쓰기는 삭제했고 HTTP는 기존대로 ANSWER_READY 답변을 동일 요청에 재사용한다.
- 세션 테이블의 Kotlin 이름과 필드는 MemoryConversation/일반 요청 용어로 바꿨다.
  기존 HTTP 데이터가 들어 있는 물리 테이블 이름과 사용 중인 컬럼은 그대로 매핑한다.
  기존 COMPLETED 상태는 읽기 호환으로만 남기며 신규 HTTP 요청에는 생성하지 않는다.
- HTTP 인증·지식 입력·검색·대화, 사용자 UserId와 등록 정보, ACL, 색인 outbox는 유지했다.
- 현재 README와 AGENTS를 HTTP 기준으로 갱신했다. Slack 등록을 전제한 과거 DB 초기화 계획은
  폐기 표시했고 과거 완료 문서는 이력으로 남겼다. 실제 DB/운영 환경은 변경하지 않았다.

## 검증

기존 lifecycle 테스트에서 Slack start/close 이벤트를 허용하지 않도록 변경해 실패를 확인한 뒤
런타임을 제거했다. HTTP 인증·사용자별 격리·지식 주입과 관련 use case, 사용자 저장소, lifecycle
종료 실패 처리, 대화 세션 집중 테스트를 통과했다. 기존 물리 schema로 만든 HTTP session과
ANSWER_READY receipt를 새 코드가 읽고, 중복 요청을 차단하며 정확히 10분 후 만료하는 회귀
테스트를 추가해 통과했다.

`:app:dependencyInsight --dependency com.slack.api --configuration runtimeClasspath` 결과는
일치하는 의존성 없음이다. 최종 전체 테스트 결과는 아래에 기록한다.

최종 `gradlew.bat test` 성공. XML 집계 175개 테스트가 실패·오류·skip 없이 통과했고,
배포 스크립트의 모의 runtime 검증 10개도 통과했다. 실제 외부 서비스는 실행하지 않았다.
