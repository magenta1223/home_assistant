# Semantic index integration 모듈 분리

- 상태: TODO
- 우선순위: P1
- 선행 작업: [onnx-embedding-runtime-migration.md](../p0/onnx-embedding-runtime-migration.md)

## 문제

현재 `adapter-outbound`에는 application output port가 아닌 `TextEmbedder`와 `VectorStore`가
최상위 기술 capability처럼 선언돼 있다. Qdrant HTTP transport, standalone process lifecycle과
distribution 설치도 같은 모듈에 있고, 실제 application port인 `SemanticMemoryIndexWriter`와
`SemanticMemoryIndexSearcher` 구현이 이 기술 API들을 조합한다.

결과적으로 다음 세 수준이 한 모듈에 섞여 있다.

- Ollama와 향후 ONNX Runtime의 embedding integration
- Qdrant client, server runtime과 vector protocol
- memory ID, 접근 scope와 payload를 변환하는 semantic-memory outbound adapter

`TextEmbedder`와 `VectorStore`는 application 요구사항이 아니라 adapter 내부 조립을 위한 SPI다.
Codex integration과 같은 기준을 적용하면 기술 integration을 별도 모듈로 분리하고 실제 port
mapping만 `adapter-outbound`에 남기는 편이 경계를 정확히 표현한다.

## 목표

- ONNX embedding 기술을 `integration-onnx`에, Qdrant 기술을 `integration-qdrant`에 분리한다.
- `adapter-outbound`에는 두 integration을 조합해 semantic-memory application output port를
  구현하는 adapter만 남긴다.
- integration 모듈은 application/domain을 의존하지 않는다.
- application의 768차원, memory payload, 접근 scope와 ranking 계약을 integration API로
  밀어 넣지 않는다.
- ONNX P0 전환과 Qdrant 전체 재색인 동작을 보존한다.

## 목표 구조

```text
integration-onnx/       # model bundle, tokenizer, session, embedding vector
integration-qdrant/     # HTTP protocol, collection, vector point, process lifecycle
runtime-distribution/   # 검증된 외부 binary/model 설치 기반

adapter-outbound/
  semanticindex/memory/ # SemanticMemoryIndexWriter/Searcher 구현과 domain mapping
```

## 구현 순서

1. ONNX P0 완료 후 실제 embedder API, bundle lifecycle과 Qdrant 재색인 경계를 기준선으로 고정한다.
2. `TextEmbedder`, `VectorStore`, vector filter/result와 managed runtime type의 사용처를 분류한다.
3. application/domain 의존성이 없는 `integration-onnx`와 `integration-qdrant` 모듈을 만든다.
4. ONNX session과 tokenizer resource ownership을 `integration-onnx`로 이동한다.
5. Qdrant transport, protocol DTO, collection 관리, process/runtime와 setup을
   `integration-qdrant`로 이동한다.
6. 기존 generic `VectorStore`를 speculative provider abstraction으로 유지하지 않는다. 실제
   Qdrant integration에 필요한 최소 API를 노출하고 다른 provider가 생길 때 일반화를 검토한다.
7. `adapter-outbound`의 semantic-memory adapter가 query/passage prefix, memory ID, ACL filter와
   payload mapping을 소유하게 한다.
8. `ApplicationServicesFactory`와 `MemoryReindex`가 기능별 semantic-index factory를 통해
   integration resource를 조립하게 한다.
9. module dependency 검사, embedding 회귀, Qdrant integration, 전체 재색인과 검색 회귀 테스트를
   실행한다.

## 완료 조건

- ONNX와 Qdrant integration 모듈이 application/domain/adapter 모듈을 의존하지 않는다.
- `adapter-outbound` 최상위에 독립 port처럼 보이는 `embedding`과 generic `vector` package가 남지
  않는다.
- application output port 구현은 semantic-memory 기능 package에서 명확히 확인된다.
- E5 prefix, memory payload와 access-scope mapping은 integration이 아니라 outbound adapter가
  소유한다.
- model/session, Qdrant process와 HTTP resource lifecycle 소유자가 하나씩 존재한다.
- ONNX reference 출력, Qdrant 저장·검색, 전체 재색인과 memory search 회귀 테스트가 통과한다.
- 전체 `gradlew test`가 통과한다.

## 제외 범위

- ONNX P0 구현과 production vector migration을 이 작업에서 다시 수행
- embedding provider 또는 vector store의 플러그인 일반화
- Qdrant 교체
- semantic search 계약과 ranking 변경

## 현재 상태 (2026-09-06)

미구현이다. `integration-onnx`, `integration-qdrant` 모듈은 없으며 `TextEmbedder`, `VectorStore`,
Ollama와 Qdrant runtime 구현은 계속 `adapter-outbound`에 있다. 선행 작업인 ONNX embedding 전환이
완료될 때까지 P1로 유지한다.
