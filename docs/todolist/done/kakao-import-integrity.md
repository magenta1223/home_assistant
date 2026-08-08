# Kakao import 무결성과 문맥

- 상태: DONE
- 완료일: 2026-08-08
- 목표: Weekend MVP 및 대용량·증분 분석 안정화

## 문제

dedup fingerprint에 파일명이 포함되어 같은 대화를 다른 파일명으로 올리면 중복 저장됐다.
Bracket export의 날짜 구분자가 message 시각에 결합되지 않아 날짜가 다른 동일 메시지를 구분할 수 없었고,
증분 import와 chunk 경계에서는 이전 문맥이 사라졌다. 긴 문서는 모든 chunk를 동시에 분석하고 전체
후보를 한 번에 merge해 Codex 호출 수와 merge 입력 크기도 제한되지 않았다.

## 실제 변경 내용

1. Kakao fingerprint를 정규화한 `sender + 실제 날짜·시각 + content`로 계산하고 파일명을 제외했다.
2. bracket 날짜 구분자와 한국어·dotted 전체 timestamp를 같은 timestamp/content로 정규화했다.
3. bracket, 한국어 timestamp, dotted timestamp export fixture를 추가했다.
4. 증분 업로드에 포함된 기존 analyzed prefix와 같은 source의 최근 analyzed record를 합쳐 최대 20개를
   읽기 전용 context로 제공한다.
5. context는 `c{id}`, 분석 대상은 `r{id}`로 구분하고 extractor가 context evidence를 거부한다.
6. 400개 record chunk에 20개 overlap을 적용하고 동시 Codex chunk 호출을 최대 4개로 제한한다.
7. merge JSON이 100,000자를 넘으면 LLM merge 대신 visibility 보존형 결정론적 중복 제거를 적용한다.

## 사용자에게 보이는 변화

- 파일명만 바꾼 같은 Kakao export는 다시 저장되지 않는다.
- bracket export memory 분석 입력에 실제 날짜가 보존된다.
- 이전 export에 새 메시지가 추가된 증분 업로드는 직전 대화 문맥을 참고하되 신규·재시도 record만
  memory evidence로 저장한다.
- 큰 export도 Codex chunk 호출과 merge 입력 크기에 상한이 있다.

## 검증 결과

- 세 Kakao export fixture의 정규화 결과와 multiline message 보존을 검증했다.
- 파일명 독립 dedup key와 서로 다른 bracket 날짜의 key 분리를 검증했다.
- renamed 누적 export 및 같은 source의 tail-only 증분 context를 검증했다.
- context evidence 거부, 1,001-record overlap, overlap-only chunk 방지, 최대 동시 호출 2개 설정,
  oversized merge fallback을 검증했다.
- `./gradlew build` 통과.

## 남은 제약

- 이 변경 이전에 파일명 기반 fingerprint로 저장된 기존 row는 자동 재작성하지 않는다. 기존 evidence
  참조와 unique key 충돌을 안전하게 합치는 별도 data migration이 필요하다.
- 날짜 구분자가 없는 headerless bracket 입력은 날짜를 알 수 없어 `UNKNOWN_DATE` 기반으로 dedup한다.
- 이름이 바뀐 파일이 과거 record 없이 새 tail만 포함하면 동일 대화임을 식별할 안정적인 chat ID가 없어
  DB context를 연결하지 않는다. 누적 export에 포함된 기존 fingerprint가 있으면 이름과 무관하게 연결된다.
- oversized merge fallback은 exact content/evidence 중복만 제거하며 semantic duplicate 병합은 수행하지 않는다.
