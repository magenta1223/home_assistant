# Slack 지식 주입

- 상태: CANCELED
- 우선순위: Feature P1
- 선행 작업: Feature P0 Slack 운영 채널 아키텍처
- 종료일: 2026-08-20

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

## 취소 사유

현재 제품 방향은 Slack을 사용자 등록과 memory 기반 답변 채널로만 사용하고, source upload와 memory
생성은 로컬 `/knowledge` 페이지에서만 수행하도록 명시한다. 이 계획은 그 경계를 위반하므로 구현하지
않고 취소한다.

## 실제 상태와 검증

- Slack adapter에는 지식 주입 진입점, source 수집 modal, audience 선택 또는 import 실행 경로가 없다.
- 명시적 PUBLIC/RESTRICTED audience를 사용하는 지식 주입은 로컬
  `/api/knowledge/import/analyze` 경로에 구현되어 있다.
- 2026-08-20: `:application:test`, `:adapter-inbound:test`, `:adapter-outbound:test` 통과

## 사용자에게 보이는 변화

Slack에서는 등록과 기억 기반 질문만 제공한다. 지식 추가는 로컬 `/knowledge` 페이지에서 수행한다.

## 남은 제약

- Slack 지식 주입을 다시 도입하려면 제품 방향, 권한·감사 경계와 로컬 전용 쓰기 정책을 먼저
  재결정해야 한다.
