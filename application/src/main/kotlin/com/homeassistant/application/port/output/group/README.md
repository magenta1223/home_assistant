# Group organization output ports

이 경계는 trusted internal 조직 작업과 검색 순위용이다. 사용자에게 안전한 projection을 제공하지 않는다.

| Port | 계약 |
|---|---|
| `PendingTopicSource` | Group 미소속 Topic ID·벡터 버전을 ID 순서로 제한 조회 |
| `TopicEmbeddingGenerator` | Topic의 현재 벡터 제공; 요청 버전이 지난 벡터는 `TopicEmbeddingStore`가 거절 |
| `TopicEmbeddingStore` | key별 불변 벡터 저장; 경쟁 생성 시 먼저 저장한 벡터 반환 |
| `GroupReader` | 모든 Group의 소속 Topic·centroid 조회 (검색용, ACL 필터 없음) |
| `GroupSnapshotReader` | 같은 DB snapshot의 Group 소속·revision·centroid·사용된 Topic 벡터 조회 |
| `GroupChangeWriter` | 기존 Group 집합을 결과 Group 집합으로 원자 교체 |

`GroupChange`는 expected Group revision, 미배치 incoming Topic, 최종 Group 집합, 사용한 벡터와
operation key를 포함한다. `0→1`, `1→1`, `1→N`, `N→1`을 지원한다. 자동 병합 정책은 없다.

확정 전에 기존 대상의 모든 Topic과 incoming Topic이 결과에서 정확히 한 번씩 나타나는지
domain의 `TopicPartition`으로 검사한다. 없는 Topic, 다른 Group에서 가져온 Topic, 빈 Group,
누락·중복, 일치하지 않는 벡터·centroid를 거절한다. 출력의 기존 Group ID는 expected에 있어야 한다.
DB의 Topic 단일 소속 제약과 revision 확인을 함께 사용한다. 결과에서 사라진 대상 Group은 삭제한다.

같은 key·같은 내용은 `AlreadyApplied`와 원래 Group ID 목록을 반환한다. key를 다른 내용으로
재사용하면 잘못된 요청으로 거절한다. 결과 목록 순서도 요청 내용의 일부다. receipt 확인은
revision 검사보다 먼저 수행한다. 충돌은 `Conflict`, 정상 확정은 `Applied`다.
정책 계산 후 충돌하여 새 판단을 내렸다면 새 key를 사용한다.

SQLite adapter는 첫 write로 receipt를 예약해 다른 writer와 직렬화한 후 revision을 확인한다.
충돌·검증 오류·저장 장애 시 예약도 롤백한다. 임베딩·정책 계산은 transaction에서 실행하지 않는다.
캐시는 Group 원자 저장 밖에서 준비할 수 있다. Group membership은 실제 사용한 불변 캐시 key를
참조하므로 재시작 후 같은 벡터를 읽는다. centroid는 동일 가중 평균의 정규화된 방향을 저장한다.

호출 흐름과 복구는 [Group use case](../../../usecase/group/README.md)를 참고한다.
