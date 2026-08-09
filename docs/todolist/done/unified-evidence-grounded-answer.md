# HTTP·Slack 통합 evidence-grounded 응답

- 상태: 취소
- 기존 우선순위: P1

## 취소 사유

메모리 기반 응답은 Slack에서만 제공하기로 결정했다. 따라서 HTTP와 Slack의 답변 경로를
통합하는 목표와 HTTP answer API는 더 이상 필요하지 않다.

대신 실제 메모리 검색, 문맥 구성, Codex 대화 세션, 요청 중복 방지와 답변 보관은 기술 중립적인
`MemoryConversation` 애플리케이션 유스케이스가 담당한다. Slack 어댑터는 Slack 사용자를 애플리케이션
`userId`로 변환하고 요청을 전달한 뒤 반환된 답변을 게시하는 역할만 담당한다.

evidence와 certainty를 답변 문맥에 더 풍부하게 제공하는 개선은 필요할 때 Slack 전용 응답 품질 작업으로
별도 계획한다.
