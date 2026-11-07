# 공개 대상 선택이 모든 사용자의 이름과 id를 노출하는 문제

- 상태: TODO
- 우선순위: P2
- 종류: known issue

## 문제

지식 입력 화면(`knowledge.html`)은 에피소드 공개 대상을 고르려고 `GET /api/knowledge/users`를 부른다.
이 endpoint는 로그인을 요구하지만 가입은 누구나 할 수 있으므로, 가입만 하면 모든 사용자의 표시 이름과
userId를 받을 수 있다. userId로 로그인할 수는 없지만 사용자 목록이 그대로 드러난다.

`UserRepository.findAll()`은 이 화면 때문에 조건 없는 조회로 남아 있다.

## 목표

- 공개 대상을 고를 때 전체 사용자 목록을 내려주지 않는다.
- 대안(이름 입력으로 대상 지정, 가입 제한 등)은 설계 때 정한다.
- 해결되면 `UserRepository.findAll()`을 없앤다.

## 완료 조건

- 로그인한 임의 사용자가 전체 사용자 목록을 받을 수 없다.
- `UserRepository`에 조건 없는 조회가 없다.
