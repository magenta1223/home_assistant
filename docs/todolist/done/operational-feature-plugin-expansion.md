# 운영 기능 플러그인 확장 기준

- 상태: CANCELED
- 우선순위: Feature P2
- 선행 작업: Feature P0 및 Slack 지식 주입 운영 결과
- 종료일: 2026-08-20

## 목표

Slack 지식 주입의 운영 결과를 바탕으로, 후속 운영 기능과 채널을 공통 기능 계약으로 안전하게
추가할 기준을 정한다.

## 계획

1. 첫 구현체의 workflow 실패율, 취소·재시도, 권한 거부, 감사 조회 요구를 평가한다.
2. 기능 manifest/registry 계약이 충분한지 검토하고 실제 반복이 확인된 필드만 일반화한다.
3. 후보 기능(예: source 상태 조회, 인덱싱 복구 안내, 제한된 운영 조회)을 기능별 권한·감사·데이터 노출 관점에서 평가한다.
4. 다른 채널을 추가할 때 채널 capability와 renderer만 구현하면 되는지 검증한다.
5. 기능별 rollout, disable, 감사 보존·검색 정책을 문서화한다.

## 완료 조건

- 두 번째 기능 또는 채널 후보를 첫 기능의 코드 복제 없이 평가·도입할 수 있다.
- 기능 계약의 안정된 부분과 아직 제품별로 남겨둘 부분이 문서화된다.
- 운영 기능 확대가 memory ACL과 기존 Slack DM 답변의 경계를 약화시키지 않는다.

## 제외 범위

- 검증 전 추상화를 위한 플러그인 SDK 공개
- 모든 Slack command를 운영 기능으로 전환하는 일괄 재작성

## 취소 사유

Slack 지식 주입에는 command 이름과 interaction callback 중복만 막는 작은 registry가 필요해
구현했다. 하지만 서로 다른 두 번째 기능의 운영 결과가 없는 상태에서 channel capability,
manifest/plugin SDK까지 일반화하는 것은 이 문서의 원칙과 YAGNI에 어긋나므로 넓은 계획은 취소한다.

## 실제 상태와 검증

- Slack app manifest와 작은 slash-command registry만 구현되었다.
- 범용 channel capability, workflow plugin 또는 공개 plugin SDK는 구현하지 않았다.
- 2026-08-20: `:application:test`, `:adapter-inbound:test`, `:adapter-outbound:test` 통과

## 사용자에게 보이는 변화

없다. 구현되지 않은 확장 계획을 현재 작업 목록에서 제거한 문서 정리다.

## 남은 제약

- 실제로 서로 다른 두 운영 기능 또는 채널에서 반복이 확인되면 그 근거를 바탕으로 새 계획을 작성한다.
