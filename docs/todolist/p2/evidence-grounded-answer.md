# Evidence-grounded 응답

- 상태: TODO
- 목표: Weekend MVP 이후

## 문제

memory는 evidence ID, certainty, type을 저장하지만 실제 Codex 답변 reference에는 content만 전달한다. 응답기는 발화 근거, 확실성, 출처를 비교할 수 없다.

## 계획

1. 검색 결과에 subject, memory type, certainty, 생성 시각을 포함한다.
2. evidence ID로 source record 내용을 bulk 조회하는 read port를 추가한다.
3. context 크기 제한 안에서 memory와 핵심 evidence 문장을 함께 렌더링한다.
4. reference를 명확한 구조로 구분하고 source text를 untrusted data로 유지한다.
5. certainty가 낮거나 evidence가 충돌하면 단정하지 않도록 응답 prompt를 수정한다.
6. 필요하면 사용자에게 근거를 함께 보여주는 응답 형식을 추가한다.

## 완료 조건

- 응답기가 memory 내용뿐 아니라 근거와 certainty를 확인할 수 있다.
- 근거가 부족한 답을 확정적으로 생성하지 않는다.
