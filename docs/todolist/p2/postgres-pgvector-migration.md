# Postgres + pgvector 전환

- 상태: HOLD — Topic이 10만 개를 넘을 때 시작
- 우선순위: P2
- 선행 작업: [Topic 중심 벡터·ACL slice와 Topic 검색](../p1/topic-centroid-acl-search.md)

## 문제

Topic 검색은 SQLite에 저장한 중심 벡터를 모두 읽어 앱에서 전수 cosine으로 비교한다. 비용이 Topic 수에 비례한다.
공개 벤치마크 기준 전수 비교는 10만 개·384차원에서 쿼리당 약 68ms(sqlite-vec, M1 Pro)이고, HNSW(pgvector)는
118만 개에서도 p95 약 6ms다. 현재 구현은 벡터를 JSON으로 저장해 이보다 느릴 수 있다.

## 결정 (2026-10-20)

- 지금은 SQLite 전수 비교를 유지한다. 별도 vector DB(Qdrant)는 원본 DB와의 동기화 비용 때문에 쓰지 않는다.
- Topic이 10만 개를 넘으면 SQLite를 Postgres + pgvector(HNSW)로 바꾼다. join과 중심 벡터 갱신은 한 transaction을 유지한다.

## 범위

| 위치 | 변경 |
|---|---|
| adapter-outbound persistence | SQLite 연결·legacy migration을 Postgres로 교체. Exposed 테이블과 쿼리는 대부분 그대로 사용 |
| adapter-outbound `TopicRepository` | 중심 벡터를 vector 열과 HNSW 인덱스로 저장하고 `ORDER BY <=> LIMIT`로 검색 |
| application `TopicReader` | 전체 중심 벡터 조회 대신 질문 벡터로 상위 Topic을 받는 계약으로 변경 |
| application `GroupReader`, `BackboneSelection` | 검색의 kNN 이웃(Group·Topic 전체 기준 10개)을 앱 전수 비교 대신 같은 HNSW 질의로 받음. 이웃 링크는 저장하지 않는다 |
| app | Windows Postgres runtime 설치·기동 |
| 테스트 | SQLite 임시 파일 테스트를 Postgres 테스트 환경으로 이전 |

## 완료 조건

- Topic 검색 결과와 ACL slice 필터가 전환 전과 같다.
- 기존 SQLite 데이터를 Postgres로 옮기고 검증한다.
