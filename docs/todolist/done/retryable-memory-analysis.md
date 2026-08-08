# 재시도 가능한 Memory 분석

- 상태: DONE
- 목표: Weekend MVP

## 문제

source record 저장 후 Codex가 실패하면 record가 중복 처리 대상으로 남아 같은 파일을 다시 분석할 수 없다.

## 계획

1. source record에 최소 `PENDING`, `ANALYZED` 상태를 둔다.
2. 신규 record는 `PENDING`으로 저장한다.
3. 같은 record가 다시 들어왔을 때 `PENDING`이면 재분석 대상으로 반환한다.
4. memory 저장까지 성공한 뒤 source record를 `ANALYZED`로 변경한다.
5. 기존 DB record는 migration 시 `ANALYZED`로 취급해 과거 memory 중복 생성을 막는다.
6. memory 저장과 상태 변경 사이의 crash 가능성은 후속 idempotency 작업으로 보완한다.
7. 완전 중복과 재시도 가능한 실패를 사용자 응답에서 구분한다.

## 완료 조건

- Codex 실패 후 같은 파일을 다시 올리면 분석이 재시도된다.
- 성공한 파일을 다시 올려도 중복 memory가 생성되지 않는다.
- 기존 DB migration 후 과거 source가 다시 분석되지 않는다.
