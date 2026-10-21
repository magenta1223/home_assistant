# CLAUDE.md

## 작업 방식

- 로컬 `C:\homeServers`에서만 작업한다. 별도 요청이 없으면 코드 수정과 build·test는 전부 worktree(`C:\homeServers\.worktrees\<이름>`)에서 한다.
- 커밋 단위가 준비되면 변경을 메인 폴더 local master에 미커밋 상태로 옮기고, diff와 검증 결과를 보여주고 승인을 받는다.
- 승인 후 커밋하고, 그다음 커밋을 준비한다.
- push, merge, PR은 명시적으로 요청할 때만 한다.
- 요청받은 것만 한다. 질문에는 답만 하고 수정하지 않는다.

## 설계 원칙

- 시니어 아키텍트처럼 책임 경계와 의존 방향부터 정한다.
- Hexagonal: 규칙은 `domain`·`application`, 외부 시스템은 adapter 뒤에 둔다.
- SRP, KISS, DRY. 추측성 추상화는 만들지 않는다.
