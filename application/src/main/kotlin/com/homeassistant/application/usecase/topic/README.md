# Memory → Topic assignment

아직 어떤 Topic에도 속하지 않은 Memory를 하나 이상의 Topic에 배정하고, 이어서 Topic을 Group으로 정리한다.
내부 input port는 `OrganizePendingMemories.execute(limit)`이며 앱의 `organizeMemories`로 제공된다.
HTTP·worker·Memory 분석 경로에서는 호출하지 않는다. 앱 시작만으로 실행되지 않는다.

xMemory 대응은 Source가 segment, Memory가 component, Group이 group이다. Topic은 xMemory에 없는 층으로,
한 Memory가 여러 묶음에 속하는 복수 소속을 담는다. Group의 xMemory 분할은 한 원소가 한 group에만
속한다고 가정하므로, 겹침은 Topic이 맡고 분할은 Topic 위의 Group이 맡는다.

## 정상 흐름

```mermaid
sequenceDiagram
    actor Caller as 호출자
    participant Batch as OrganizePendingMemoriesService
    participant Assign as AssignMemoryToTopics
    participant Embed as 임베딩 캐시
    participant Model as TopicAssignmentGenerator (LM)
    participant Store as Topic DB
    participant Groups as Group 정리
    Caller->>Batch: 1. organizeMemories(limit)
    Batch->>Store: 2. Topic 소속이 없는 Memory 조회
    loop Memory마다
        Batch->>Assign: 3. 배정 요청
        Assign->>Store: 4. 전체 Topic 목록(설명)
        Assign->>Embed: 5. Memory·Topic 설명 벡터
        Assign->>Assign: 6. cosine 상위 5개 = 후보
        Assign->>Model: 7. Memory + 후보 Topic
        Model-->>Assign: 8. 참여 Topic 여러 개(분류·수정 설명) 또는 새 Topic
        Assign->>Store: 9. 새 Topic·소속 추가·설명 교체를 한 transaction으로
        Assign-->>Batch: 10. 결과(참여·생성 Topic ID)
    end
    Batch->>Groups: 11. 설명 바뀐 Topic 재임베딩 → 그 Group 중심만 갱신
    Batch->>Groups: 12. 새 Topic을 xMemory 방식으로 Group 배치·분할
    Batch-->>Caller: 13. Memory별 결과 + Group 결과
```

## 정책

- 한 Memory는 여러 Topic에 속할 수 있다. 모델은 후보 중 해당하는 Topic을 모두 고르고, 없으면 새 Topic을 만든다.
  최소 한 Topic에는 배정한다. 후보가 아닌 Topic ID를 고르면 그 Memory는 `Failed`다.
- Memory가 참여한 Topic은 소속이 바뀌었으므로 모델이 낸 수정 설명으로 같은 batch에서 교체한다.
  전체 소속을 다시 읽지 않고 기존 설명과 새 Memory로 갱신하므로 Topic 크기와 무관하게 호출이 한 번이다.
- 분류명은 모델이 Topic 안에서 정한다. 새 Topic의 creation key는 `memory:{memoryId}:{순번}`이다.
- Memory 벡터는 `memory-content:passage:v1`, Topic 벡터는 `topic-description:passage:v1` 규약이며 같은 모델을 쓴다.
- 배정 대기는 "Topic 소속이 하나도 없음"으로 판단한다. 수동으로 소속을 추가한 Memory도 배정된 것으로 본다.

## 실패·복구와 신뢰 경계

- 임베딩·모델 호출은 DB transaction 밖에서 수행한다. 저장은 `TopicMaintenanceStore`의 원자 batch다.
- 개별 Memory 실패는 `Failed`이며 다음 Memory를 계속 처리한다. 저장이 실패하면 Memory가 대기 상태로 남아 다음 호출이 다시 배정한다.
- Group 재임베딩·배치 단계의 임베딩 장애는 호출자에게 전파한다. 앞 단계의 저장 결과는 유지되고 다음 호출이 이어간다.
- 모델은 ACL이 다른 Memory로 만든 Topic 설명을 후보로 읽는다. Topic 설명은 trusted internal 메타데이터이며
  사용자 검색·응답에 아직 연결하지 않는다.
- 후보 검색은 매번 전체 Topic 벡터를 비교한다. 가족 규모를 전제로 하며 ANN 색인은 없다.
- Topic 분할, Topic·Group 병합과 kNN 링크는 [후속 작업](../../../../../../../../../docs/todolist/p1/topic-group-merge-and-knn-links.md)이다.
  검색 연결과 한국어 품질 검증도 구현하지 않았다.

검증은 fake port application 테스트와 임시 SQLite 통합 테스트를 사용한다. 실제 LM·임베딩 서버는 호출하지 않는다.
