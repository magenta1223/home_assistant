# LM 기반 Topic 생성 제거

- 상태: DONE (2026-10-10)
- 요청일: 2026-10-10
- 요청: Topic을 LM으로 생성하지 않도록 해당 구현을 삭제한다.

## 범위와 순서

1. Memory 분석 후 Topic 생성 호출과 앱의 Topic worker 실행을 제거한다.
2. LM adapter·prompt·응답 계약과 이를 위한 배치 service·port·검증·복구 조회를 삭제한다.
3. Topic domain·스키마·명시적 원자 저장은 유지한다. 대체 생성 알고리즘은 추가하지 않는다.
4. 호출 순서와 남은 기능을 문서에 반영하고 관련 테스트 및 전체 테스트를 실행한다.

## 완료 조건

- 실행 코드에 LM Topic 생성·자동 배치·worker 연결이 없다.
- Memory 추출·저장·색인 outbox와 Topic 명시적 저장·ACL 보존 테스트가 통과한다.
- 기존 데이터는 수정하거나 삭제하지 않는다.

## 결과와 검증

LM Topic adapter·prompt·JSON 응답 계약, 배치 service·validator·input/output port, worker와 앱 조립을
삭제했다. 배치 전용 미배치/후보 조회와 조건부 저장도 제거했다. Topic domain·테이블·명시적 원자
저장 계약은 유지한다. 현재 앱에는 Topic 자동 생성 경로가 없다. 별도 생성 방식은 추가하지 않았다.

Memory 분석 후 Topic 호출이 없어야 한다는 테스트가 기존 코드에서 실패하는 것을 확인한 후
호출부를 삭제했다. 분석 저장 테스트, Topic 저장·관계·롤백·ACL 보존, 앱 lifecycle 집중 테스트를
통과했고 최종 `gradlew.bat test`도 통과했다. XML 집계 207개 테스트의 실패·오류·skip은 0개다.
별도로 배포 스크립트의 모의 runtime 테스트 10개도 통과했다.
삭제한 동작만 검증하던 테스트는 함께 제거했으며 Source/Memory 추출·저장·색인·검색·세션 회귀
테스트는 유지했다. 실제 LM 호출, 운영 서비스 실행, DB 데이터 변경은 하지 않았다.
