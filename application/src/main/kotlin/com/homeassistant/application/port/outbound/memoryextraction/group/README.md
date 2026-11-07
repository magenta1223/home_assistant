# Group organization output ports

이 경계는 trusted internal 조직 작업과 검색 순위용이다. 사용자에게 안전한 projection을 제공하지 않는다.

| Port | 계약 |
|---|---|
| `ComponentEmbeddingStore` | key별 불변 벡터 저장; 요청 버전이 지난 벡터는 거절; 경쟁 생성 시 먼저 저장한 벡터 반환 |
| `GroupRepository` | `findSnapshot`: 같은 DB snapshot의 Group 소속·revision·centroid·사용된 Component 벡터 조회 (조직·검색용, ACL 필터 없음). `findVisibleTo`: 사용자가 읽을 수 있는 소속만 남긴 Group과 저장된 중심 (검색용, 읽을 수 있는 소속이 없는 Group은 제외). `save`: 기존 Group 집합을 결과 Group 집합으로 원자 교체 |

`findSnapshot`은 조건 없는 유일한 조회다. 배치는 모든 Group 중 가장 가까운 centroid를 고르고, 분할·병합은 partition 전체 점수를
비교하므로 모든 Group이 판단의 입력이다.

`GroupChange`는 expected Group revision, 미배치 incoming Component, 최종 Group 집합, 사용한 벡터를
포함한다. `0→1`, `1→1`, `1→N`, `N→1`을 지원한다. 자동 병합 정책은 없다.

확정 전에 기존 대상의 모든 Component와 incoming Component가 결과에서 정확히 한 번씩 나타나는지
domain의 `ComponentPartition`으로 검사한다. 없는 Component, 다른 Group에서 가져온 Component, 빈 Group,
누락·중복, 일치하지 않는 벡터·centroid를 거절한다. 출력의 기존 Group ID는 expected에 있어야 한다.
DB의 Component 단일 소속 제약과 revision 확인을 함께 사용한다. 결과에서 사라진 대상 Group은 삭제한다.

충돌은 `GroupChangeConflict` 예외, 정상 확정은 결과 Group 목록이다. 이미 확정된 변경을 다시 보내면
revision이 올라가 있으므로 충돌이다. 충돌하면 새 snapshot으로 다시 판단한다.

SQLite adapter는 다른 writer가 DB lock을 잡고 있어 거절된 쓰기(`SQLITE_BUSY`)도 `GroupChangeConflict`로 알린다.
충돌·검증 오류·저장 장애 시 모든 변경을 롤백한다. 임베딩·정책 계산은 transaction에서 실행하지 않는다.
캐시는 Group 원자 저장 밖에서 준비할 수 있다. Group membership은 실제 사용한 불변 캐시 key를
참조하므로 재시작 후 같은 벡터를 읽는다. centroid는 동일 가중 평균의 정규화된 방향을 저장한다.

호출 흐름과 복구는 [Group use case](../../../usecase/group/README.md)를 참고한다.
