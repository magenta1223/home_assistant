# Component·Group 처리량

- 상태: TODO
- 우선순위: P2
- 선행 작업: [Episode 분석 파이프라인 outbox 전환](../done/episode-analysis-outbox-pipeline.md)

## 문제

Episode가 Component·Group으로 정리되는 속도가 Episode 수에 비례해 느려진다.

- `UpdateMemoryWorker`는 스레드 하나(`component-assignment-outbox`)에서 Episode를 하나씩 처리한다
  (`adapter-inbound/.../scheduler/UpdateMemoryWorker.kt`).
- Episode마다 `ComponentExtractorImpl.extract`가 모델을 차례로 1~2번 호출한다. 기존 Component가 없으면 1번,
  있으면 예측과 추출로 2번이며 `runBlocking`으로 끝날 때까지 기다린다
  (`adapter-outbound/.../componentextraction/ComponentExtractorImpl.kt`).
- Component 하나를 Group에 넣을 때마다 `OrganizeComponentIntoGroups`가 `GroupRepository.findSnapshot()`으로
  모든 Group 소속과 그 embedding을 읽는다 (`OrganizeComponentIntoGroups.kt`, `GroupRepositoryImpl.findSnapshot`).
  Group은 모든 사용자가 공유하므로 이 비용은 전체 Component 수에 비례한다.

처리 시간은 대략 Episode 수 × 모델 호출 1~2회 × 호출당 시간이다. 호출당 시간은 측정하지 않았다.
큰 채팅방 전체를 주입하면 정리가 끝날 때까지 오래 기다린다.

## 목표

- Episode 분석과 같은 전역 모델 동시 실행 한도 안에서 Component 배정을 병렬로 처리해, 처리 시간을 동시 실행 수만큼 줄인다.
- Component 하나를 배치할 때 후보 Group만 읽어, 처리 비용이 전체 데이터 크기와 무관하게 한다.

## 검토할 것

- 전역 모델 동시 실행 한도를 Episode 분석 worker와 Component 배정 worker가 함께 지키는 방법.
- `findSnapshot`은 배치·분할·병합이 partition 전체를 보는 이유로 둔 조건 없는 조회다
  ([group port README](../../../application/src/main/kotlin/com/homeassistant/application/port/outbound/memoryextraction/group/README.md)).
  배치는 centroid만으로 후보를 고르고 분할·병합은 고른 Group만 읽도록 나눌 수 있는지 판단한다.
- 병렬 배정이 Group 저장의 revision 충돌을 늘리는지와 재시도 상한.

## 시점

지금 사용자 규모(4~8명)에서는 급하지 않다. 큰 방 전체 주입 뒤 정리 대기가 문제가 될 때 다룬다.
