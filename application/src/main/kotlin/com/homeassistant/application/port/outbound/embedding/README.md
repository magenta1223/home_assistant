# Embedding output ports

| Port | 계약 |
|---|---|
| `EmbeddingGenerator` | 한 모델의 document·query 벡터를 같은 `EmbeddingSpace`에서 만든다 |
| `EpisodeEmbeddingStore` | Episode document 벡터를 Episode ID·content hash·모델·입력 규약별로 불변 저장; 내용이 바뀐 Episode의 벡터는 거절; 경쟁 생성 시 먼저 저장한 벡터 반환 |
| `ComponentEmbeddingStore` | Component 벡터를 key별로 불변 저장; 요청 버전이 지난 벡터는 거절; 경쟁 생성 시 먼저 저장한 벡터 반환 |

저장소는 key마다 따로 둔다. 재사용·생성 흐름은 `usecase/embedding`의 `PrepareEpisodeEmbedding`, `PrepareComponentEmbedding`이 맡는다.
