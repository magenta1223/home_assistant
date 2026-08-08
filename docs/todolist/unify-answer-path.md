# HTTP와 Slack 답변 경로 통일

- 상태: TODO
- 목표: Weekend MVP 이후

## 문제

Slack은 검색 context를 Codex에 전달해 답변하지만 HTTP answer endpoint는 첫 번째 memory content를 붙여 반환한다. 같은 질문이 진입 경로에 따라 다르게 처리된다.

## 계획

1. memory 검색과 answer generation을 별도 use case로 명확히 나눈다.
2. 공통 answer generator port와 prompt builder를 application에 둔다.
3. Slack과 HTTP가 같은 검색·context·answer generation 흐름을 사용하게 한다.
4. HTTP가 디버깅용 검색 endpoint라면 이름과 계약을 answer가 아닌 search로 변경한다.
5. no-match와 provider failure 정책을 두 경로에서 통일한다.

## 완료 조건

- 동일 사용자와 질문은 Slack과 HTTP에서 같은 memory context를 사용한다.
- answer와 raw search의 API 의미가 구분된다.
