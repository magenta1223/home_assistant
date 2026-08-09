# 모델이 추론하는 Memory visibility

- 상태: DONE
- 목표: Weekend MVP

> 이 방식은 `explicit-knowledge-audience.md`에서 폐기되었다. 현재 LLM은 권한을 판단하지
> 않으며, 입력자가 지정한 source audience를 canonical memory가 상속한다.

## 현재 동작

현재 LLM 출력 schema에는 visibility가 없다. extractor도 visibility를 설정하지 않으므로 모든 memory가 `MemoryProposal`의 기본값인 `PUBLIC`으로 저장된다.

## 계획

1. memory extraction JSON schema에 `visibility`를 필수 필드로 추가한다.
2. 일반적인 가족 공동 정보만 `PUBLIC`으로 분류한다.
3. 건강, 금융, 자격 증명, 민감한 관계, 개인적인 고민은 `PRIVATE`으로 분류한다.
4. 판단이 애매하면 `PRIVATE`을 선택하도록 prompt에 명시한다.
5. chunk merge에서도 visibility를 유지한다.
6. 중복 후보의 visibility가 충돌하면 더 제한적인 `PRIVATE`을 선택한다.
7. import 완료 결과에서 PRIVATE/PUBLIC 개수를 확인할 수 있게 한다.

## 제약

`PRIVATE`은 업로드한 사용자에게 귀속된다. 모델은 대화 참여자와 업로더의 관계를 완전히 알 수 없으므로 자동 추론은 보수적으로 동작해야 한다.

## 완료 조건

- 모델 응답에 visibility가 항상 존재한다.
- 애매하거나 민감한 memory가 자동으로 PUBLIC이 되지 않는다.
- 사용자별 검색에서 PRIVATE 접근 제한이 유지된다.
