# Memory placement use case

`MemoryPlacementService`는 새로 저장된 memory batch를 기존 visible memory tree에 연결한다.
Extractor의 응답은 신뢰하지 않고 application에서 완전성, 참조 범위와 cycle을 검증한다.

## Memory 배치

```mermaid
sequenceDiagram
    participant Analysis as MemoryAnalysisService
    participant Placement as MemoryPlacementService
    participant Reader as MemoryReader
    participant Renderer as MemoryPlacementTreeRenderer
    participant Extractor as MemoryPlacementExtractor
    participant Validator as MemoryPlacementResponseValidator
    participant Tree as MemoryTreeStore

    Analysis->>Placement: place(userId, saved memories)
    Placement->>Placement: process-local mutex 획득
    Placement->>Reader: getMemories(userId)
    Reader-->>Placement: 기존 visible memories
    Placement->>Renderer: render(existing, maxDepth)
    Renderer-->>Placement: tree text + selectable ids
    Placement->>Extractor: analyze(new memories + visible tree)
    Extractor-->>Placement: parent decisions
    Placement->>Validator: validate(input, response, selectable ids)
    alt 유효하고 parent 연결 존재
        Placement->>Tree: attachChildren(parentByChild)
        Tree-->>Placement: 완료
    else 모두 root로 유지
        Placement-->>Analysis: 저장 동작 없음
    end
    Placement-->>Analysis: 완료
```

## 검증 규칙

- 입력 memory마다 정확히 하나의 decision이 있어야 한다.
- decision memory ID는 중복되거나 입력 집합 밖에 있을 수 없다.
- 자기 자신, 노출되지 않은 기존 memory를 parent로 선택할 수 없다.
- 같은 batch 안의 parent 연결은 cycle을 만들 수 없다.
- `MemoryTreeBootstrapService`는 기존 flat root를 같은 `MemoryPlacement` input port로 한 번에 재배치한다.
