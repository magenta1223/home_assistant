# 검색 embedding 일괄 조회

- 상태: TODO
- 우선순위: P2

## 문제

대화 검색이 질문 하나에 읽을 수 있는 항목 수만큼 DB transaction을 연다.

- `ComponentSearcher.search`가 읽을 수 있는 Component마다 `PrepareComponentEmbedding.prepare`를 부른다
  (`application/.../contextprovider/component/ComponentSearcher.kt`).
- `EpisodeSearcher.search`가 읽을 수 있는 Episode마다 `PrepareEpisodeEmbedding.prepare`를 부른다
  (`application/.../contextprovider/episode/EpisodeSearcher.kt`).
- `prepare`는 항목 하나의 embedding을 `find`로 읽고, 없으면 만들어 `save`한다. `find`가 호출마다 transaction 하나를 연다.

항목이 수천 개면 질문 하나에 쿼리가 수천 개 생긴다.

## 목표

- 읽을 수 있는 항목의 저장된 embedding을 한 번에 조회해 질문당 쿼리를 몇 개로 줄인다.
- 접근 조건은 조회 쿼리 안에서 거른다. 가져온 뒤 거르지 않는다.
- 저장된 embedding이 없는 항목만 만들어 저장한다.

## 시점

지금 사용자 규모에서는 우선순위가 낮다. 대화 응답 지연이 검색에서 생길 때 다룬다.
