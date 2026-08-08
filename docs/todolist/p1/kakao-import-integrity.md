# Kakao import 무결성과 문맥

- 상태: TODO
- 목표: Weekend MVP 일부, 나머지 후속 개선

## 문제

dedup fingerprint에 파일명이 포함되어 같은 대화를 다른 파일명으로 올리면 중복 저장된다. bracket export는 날짜 구분자를 함께 해석해야 할 수 있으며 증분 import와 chunk 경계에서는 이전 문맥이 사라진다.

## Weekend MVP 계획

1. dedup fingerprint에서 파일명을 제거한다.
2. sender, 실제 날짜·시각, content를 fingerprint 기준으로 사용한다.
3. bracket export 날짜 구분자를 인식해 이후 메시지 시각에 날짜를 결합한다.
4. 대표 Kakao export 형식별 parser fixture를 만든다.

## 후속 계획

1. 증분 import 시 최근 기존 record 일부를 읽기 전용 문맥으로 제공한다.
2. 신규 record와 context record를 구분해 모델이 신규 record만 evidence로 선택하게 한다.
3. 긴 문서는 chunk 사이에 작은 overlap을 둔다.
4. 동시에 실행하는 Codex chunk 수를 제한한다.
5. merge 입력이 커지면 계층적 merge 또는 순차 dedup으로 바꾼다.

## 완료 조건

- 파일명만 바꾼 같은 대화가 중복 저장되지 않는다.
- bracket export 날짜가 source content에 보존된다.
- 지원하는 Kakao 형식이 fixture로 명시된다.
