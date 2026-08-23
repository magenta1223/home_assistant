# Slack 지식 주입

- 상태: DONE
- 우선순위: Feature P1
- 선행 작업: Feature P0 Slack 운영 채널 아키텍처
- 종료일: 2026-08-23

## 목표

Slack 지식 주입을 운영 기능 레지스트리의 첫 구현체로 제공한다. 입력 수집부터 audience 확인,
분석 시작, 진행·결과 안내까지 공통 workflow를 사용하며 기존 로컬 `/knowledge` 흐름과 동일한
application use case를 호출한다.

## 범위

1. 등록·권한 확인된 Slack member만 기능을 시작할 수 있게 한다.
2. 텍스트와 지원 가능한 소스 형식을 단계형 상호작용으로 수집하고, source 이름과 PUBLIC/RESTRICTED audience를 명시적으로 확인한다.
3. 기존 원자적 import/analyze, idempotency, indexing outbox를 재사용한다.
4. 진행·성공·실패·재시도·취소를 요청 상관관계와 감사 이벤트에 연결한다.
5. Slack의 payload 재전송과 modal 만료가 중복 분석 또는 audience 변경을 만들지 않게 한다.

## 완료 조건

- Slack 경로와 웹 경로가 같은 입력 계약과 권한 규칙을 사용한다.
- 사용자가 분석 전 audience와 대상 내용을 확인할 수 있다.
- 실패·중복·만료 후 재시도해도 canonical memory와 권한이 손상되지 않는다.
- Slack에서 원문 또는 제한된 audience가 권한 없는 사용자에게 노출되지 않는다.

## 제외 범위

- Slack을 통한 canonical memory 직접 편집
- 대화 답변과 지식 주입의 세션·권한 상태 공유

## 실제 상태와 검증

- manifest에 `/knowledge` slash command와 `commands` scope를 선언했다.
- 등록된 사용자만 modal을 열 수 있고 source 이름·형식·PUBLIC/RESTRICTED audience·본문을 받는다.
- `KnowledgeInjectionWorkflow`가 Slack identity를 application user로 해석한 뒤 기존
  `MemoryAnalysis`를 호출한다.
- command와 interaction callback 중복을 거부하는 `SlackSlashCommandRegistry`로 listener를 등록한다.
- modal 제출은 즉시 ack한 뒤 분석을 실행하고 원래 interaction의 response URL로 진행·결과를 알린다.
- manifest 원격 갱신은 `updateSlackManifest` 작업이 validate 후 update한다.

## 사용자에게 보이는 변화

등록된 사용자는 Slack에서 `/knowledge`를 실행해 지식을 추가할 수 있다. 로컬 `/knowledge` 흐름도
그대로 유지된다.

## 남은 제약

- Slack modal의 본문 입력은 3,000자까지다. 더 큰 원문은 로컬 `/knowledge` 페이지를 사용한다.
- Slack 앱 하나의 manifest에는 slash command를 최대 50개까지 선언할 수 있다.
