# Slack 개발 동결과 사용자 기반 HTTP 접근

- 상태: DONE
- 우선순위: Feature P0
- 결정일: 2026-09-07
- 완료일: 2026-09-07
- 선행 작업: 없음
- 후속 작업: 없음

## 범위

이번 작업의 목표는 두 가지뿐이다.

1. 현재 Slack 기능은 유지하되 더 이상 추가 개발하지 않도록 deprecated 상태로 표시한다.
2. knowledge 주입과 memory conversation을 인증된 사용자 기준으로 HTTP에서 실행할 수 있게 한다.

웹페이지 변경, Tailscale, PWA, Web Push와 Notification Service는 다루지 않는다. 웹페이지가 HTTP API를
호출하는 작업은 이 계획이 끝난 다음 별도로 진행한다.

## 구현 전 상태

- `/api/knowledge/users`와 `/api/knowledge/import/analyze`는 이미 Bearer 인증을 사용한다.
- Bearer token은 `HTTP_MEMBER_API_KEYS_JSON`을 통해 application `UserId`로 변환된다.
- knowledge import의 caller는 request body가 아니라 인증 principal에서 결정된다.
- `MemoryConversation` application input port와 10분 session lease는 이미 구현되어 있다.
- memory conversation을 호출하는 실제 inbound adapter는 현재 Slack뿐이다.

따라서 새 인증 체계나 새 conversation use case를 만들 필요는 없다. 기존 HTTP principal과
`MemoryConversation`을 연결하면 된다.

## 1. Slack 개발 동결

다음 두 표시를 추가한다.

- `adapter-inbound/slack/README.md`에 `FROZEN` 상태와 허용 변경 범위를 기록한다.
- 외부 조립 진입점인 `SlackRuntimeFactory`에 `@Deprecated(level = WARNING)`와 같은 내용의 KDoc을
  추가한다.

`ERROR` level은 현재 production 조립과 test에 suppression을 추가해야 하므로 사용하지 않는다.
`AGENTS.md`에는 새 Slack command, modal, scope와 구조 refactor를 금지한다고 기록한다.

허용되는 Slack 변경은 기존 동작의 bug, 보안·데이터 손실 문제, 그리고 향후 Slack 제거뿐이다.
기존 Slack DM, `/knowledge`, Socket Mode와 manifest는 이번 작업에서 변경하지 않는다.

`slack-integration-module-extraction.md`는 구현하지 않는다. 제거 예정인 Slack을 별도 integration
모듈로 확장하지 않는다.

## 2. HTTP 사용자 기준

기존 Bearer 인증을 그대로 사용한다.

```text
Bearer token
    -> HttpUserPrincipal(UserId)
    -> application request
    -> UserId 기준 ACL 적용
```

새 cookie session, OAuth 또는 Tailscale identity header는 추가하지 않는다. protected route는 caller
`UserId`를 body나 path에서 받지 않는다.

`HTTP_MEMBER_API_KEYS_JSON`에 설정된 `UserId`는 display name 등록이 끝난 application 사용자여야 한다.
구현 전 production 설정을 read-only로 확인하고, composition에서는 미등록 ID가 있으면 protected route를
열기 전에 startup을 실패시킨다. 처음 본 사용자를 HTTP에서 등록하는 기능은 이번 범위가 아니다.

## 3. Knowledge HTTP 보강

기존 route와 use case는 유지한다.

- `GET /api/knowledge/users`
- `POST /api/knowledge/import/analyze`

추가 변경은 다음으로 제한한다.

- 사용자 목록과 오류 문구의 "Slack 사용자" 표현을 "application 사용자"로 변경한다.
- configured HTTP user가 등록 사용자인지 startup에서 검증한다.
- caller 위조, 미등록 사용자와 PUBLIC/RESTRICTED audience 회귀 test를 보강한다.

source의 `allowedUserIds`는 Memory 열람 범위이므로 계속 request body로 받는다. 이것은 knowledge를
주입하는 caller의 identity와 별개다.

## 4. Memory conversation HTTP route

다음 endpoint를 추가한다.

```http
POST /api/memory/conversation
Authorization: Bearer <token>
Content-Type: application/json

{
  "requestId": "client-generated UUID",
  "question": "질문"
}
```

HTTP adapter는 인증 principal로 application request를 만든다.

```text
participant.scopeId       = "http"
participant.participantId = authenticated UserId
participant.userId        = authenticated UserId
requestKey.streamId       = "http:<authenticated UserId>"
requestKey.requestId      = request.requestId
```

client는 `UserId`, participant, scope나 Codex thread ID를 전달할 수 없다. HTTP session은 Slack session과
분리되며 동일 HTTP 사용자는 기존 규칙대로 마지막 활동 후 10분 동안 thread를 재사용한다.

초기 계약은 단순한 동기 HTTP 응답이다. streaming이나 background job은 추가하지 않는다.

- 답변 완료 또는 같은 request의 저장된 답변: `200 OK`
- 같은 request가 이미 처리 중이거나 재사용할 answer가 없음: `409 Conflict`
- conversation runtime 미구성: `503 Service Unavailable`
- 잘못된 UUID, 빈 질문 또는 과도한 request: `400 Bad Request`
- 인증 실패: `401 Unauthorized`

기존 `MemoryConversationResult`를 그대로 사용한다. `AnswerReady`는 `200`, `AlreadyHandled`는 `409`,
`Failed`는 `503`으로 adapter에서 변환한다. 처리 중 상태를 더 세분화하기 위해 application 계약을
변경하지 않는다.

## 구현 순서

### 1. Slack 표시

- Slack package README와 `SlackRuntimeFactory` KDoc/`@Deprecated(WARNING)` 추가
- `AGENTS.md`와 TODO의 Slack 동결 방향 확인
- verify: 기존 Slack test와 compile 통과

### 2. HTTP 사용자 검증과 knowledge 회귀

- configured HTTP `UserId`의 등록 상태 startup 검증
- knowledge route의 채널 종속 문구 제거
- 인증 principal과 audience 분리 test 추가
- verify: 등록/미등록 사용자, caller 위조와 ACL test 통과

### 3. HTTP memory conversation

- `ApplicationServices`에서 기존 `MemoryConversation?` 노출
- `MemoryConversationRoutes`와 route test 추가
- `AppRoutes`에서 기존 Bearer 인증 아래 route 연결
- verify: 사용자별 ACL/session 격리, request dedup, 10분 lease와 unavailable runtime test 통과

### 4. 전체 검증

- HTTP route 목록과 운영 설정 문서 갱신
- `./gradlew test`
- `./gradlew build`

## 완료 조건

- Slack이 코드와 프로젝트 지침에서 FROZEN/deprecated로 표시된다.
- 새 Slack 기능 또는 구조 개선 계획이 활성 상태에 남아 있지 않다.
- 모든 HTTP caller가 Bearer principal의 등록된 `UserId`로 해석된다.
- knowledge import caller는 request body로 위조할 수 없다.
- 인증 사용자가 HTTP에서 자신의 ACL로 memory conversation을 수행한다.
- HTTP 사용자 사이에 restricted Memory나 conversation thread가 섞이지 않는다.
- 같은 request ID가 중복 Codex turn을 만들지 않는다.
- HTTP conversation도 10분 idle lease를 지킨다.
- 기존 Slack 기능과 전체 build가 통과한다.
- 후속 웹페이지는 이 HTTP API만 호출하면 된다.

## 제외 범위

- 웹페이지 UI와 기존 `knowledge.html` 수정
- Tailscale·HTTPS 배포 설정
- PWA, Service Worker, Web Push와 Notification Service
- 새 사용자 등록 UI와 새 인증 방식
- Slack 제거, Slack DB table rename과 integration 모듈 분리
- streaming, WebSocket, background job과 범용 `/api/chat`
- Task와 Review 구현

## 현재 상태 (2026-09-07)

- Slack package에 FROZEN README를 추가하고 `SlackRuntimeFactory`를 `@Deprecated(WARNING)`로 표시했다.
- 기존 Bearer principal과 등록 application 사용자의 일치를 route 구성 시 검증한다.
- `POST /api/memory/conversation`을 추가하고 사용자 identity, request key와 결과 mapping을 HTTP adapter가
  담당하게 했다.
- `ApplicationServices`에는 기존 `MemoryConversation?`을 route로 전달하는 wiring만 추가했다.
- application, domain과 adapter-outbound 계약은 변경하지 않았다.
- `./gradlew test`와 `./gradlew build`가 통과했다.

배포 환경의 `HTTP_MEMBER_API_KEYS_JSON`을 실제 등록 `UserId`에 연결하고 HTTP 요청을 smoke test했다.
token 값과 사용자 mapping은 저장소에 commit하지 않는다.

## 후속 UI 진행 (2026-09-07)

- `HTTP_MEMBER_API_KEYS_JSON`을 production의 실제 두 등록 사용자에 연결했다.
- production HTTPS에서 두 token의 knowledge API 인증, 무인증 차단과 conversation route를 smoke test했다.
- 원래 제외 범위였던 웹 UI는 후속 사용자 요청으로 `/conversation` 페이지에 별도 구현했다.
- UI는 token을 브라우저 저장소에 보존하지 않고, client UUID와 질문만 기존
  `/api/memory/conversation`에 전송한다.
- `/knowledge`와 `/conversation` 사이에 이동 링크를 추가했다.
- route test와 전체 `test`, `build`, production 배포와 Tailnet HTTPS smoke test가 통과했다.

## 사용자에게 보이는 변화

- 등록 사용자는 Slack 없이 `/knowledge`에서 지식을 입력하고 `/conversation`에서 자신의 ACL로
  Memory를 조회할 수 있다.
- 인증 token은 브라우저 저장소에 남지 않으며 사용자별 conversation과 10분 idle lease가 유지된다.

## 남은 제약

- HTTP 접근은 현재 API key 방식이며 사용자 등록 UI는 없다.
- Slack은 호환성 유지만 하는 frozen legacy adapter로 남아 있고 새 기능은 추가하지 않는다.
