# Slack memory 응답 application 경계 정리

- 상태: DONE
- 완료일: 2026-08-20
- 관련 계획: Feature P0 Slack 운영 채널 아키텍처

## 문제

Slack inbound adapter가 사용자 조회, 등록 필요 판단, 최초 질문의 임시 보관, 등록 완료 후 질문 재개와
memory conversation 호출 순서를 직접 소유했다. 최초 질문은 프로세스 메모리에만 있어 재시작 시
유실됐고, 다른 inbound 채널이 같은 workflow를 재사용할 수 없었다.

## 실제 변경

1. 기술 중립적인 `MemoryAnswerWorkflow` application input port와 `MemoryAnswerWorkflowService` use case를 추가했다.
2. 외부 conversation identity의 사용자 resolve, 등록 필요·대기 결과, 표시 이름 검증, 등록 완료와 최초 질문 재개를 application으로 이동했다.
3. 최초 질문을 보존하는 `PendingRegistrationQuestionStore` output port와 SQLite 저장소를 추가했다.
4. Slack adapter는 workspace 확인, Slack payload 변환, modal/block 렌더링, 메시지 전달과 delivery 확인만 담당한다.
5. 표시 이름의 공백 제거와 50자 제한을 `RegisteredUser` domain invariant로 통합했다.
6. Codex CLI가 없어도 사용자 등록은 완료되고, 답변 불가 결과만 채널 표현으로 변환하는 기존 동작을 유지했다.
7. 등록 안내 전달이 실패하면 application의 pending 상태를 해제해 같은 Slack 이벤트를 안전하게 재처리할 수 있게 했다.

## 검증

- application test: 등록된 identity 변환, 최초 질문 보존, 중복 질문 대기, 등록 검증, 등록 후 질문 재개, 답변 비활성 상태
- adapter-inbound test: Slack 요청 변환, 등록 UI 렌더링, application 결과 전달, workspace 차단
- adapter-outbound test: 최초 질문의 첫 요청 우선 저장, 재개 후 삭제, DB reopen 내구성
- `:application:test :adapter-inbound:test :adapter-outbound:test` 통과
- 전체 `test`와 `build` 통과

## 남은 범위

Feature P0의 기능 registry, 공통 interaction 상태, 감사 이벤트와 Slack 지식 주입은 후속 작업이다.
