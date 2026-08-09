# 명시적 지식 열람자와 로컬 주입 페이지

- 상태: DONE
- 목표: 지식 쓰기 경로와 답변 경로 분리

## 결정

- `PUBLIC`은 등록된 모든 사용자가 볼 수 있다.
- `RESTRICTED`는 명시적인 `allowedUserIds`만 볼 수 있다.
- 카카오 대화는 대화 참여자들의 application user ID를 선택한다.
- 개인 지식은 해당 개인 한 명만 선택한다.
- `createdByUserId`는 입력자를 기록하는 감사 정보이며 열람 권한을 결정하지 않는다.
- LLM은 권한을 추론하거나 출력하지 않는다.
- Slack은 memory 기반 DM 답변만 제공하고 지식 입력은 로컬 웹 페이지가 담당한다.

## 구현

1. source record와 canonical memory에 PUBLIC/RESTRICTED access와 viewer 관계를 저장한다.
2. canonical memory는 evidence source의 access를 상속한다. 서로 다른 제한 범위가 섞이면
   viewer 교집합을 적용하고 빈 교집합은 저장을 거부한다.
3. 기존 PRIVATE memory는 creator 한 명을 viewer로 갖는 RESTRICTED memory로 migration한다.
4. `/knowledge`에서 source type, source name, 열람자, 텍스트/파일을 입력한다.
5. `/api/knowledge/import/analyze`가 기존 원자적 분석·저장 use case를 호출한다.
6. Slack 파일 수신과 Kakao 분석 workflow를 제거한다.
7. 서버는 로컬 사용을 위해 `127.0.0.1`에 bind한다.

## 검증

- PUBLIC, 복수 viewer RESTRICTED, evidence viewer 교집합을 테스트한다.
- 같은 source를 다른 audience로 재입력하면 기존 권한을 조용히 변경하지 않고 거부한다.
- knowledge page 제공, 인증된 사용자 목록, 명시적 audience 요청 mapping을 HTTP 테스트한다.
- 전체 Gradle test와 build를 통과한다.
