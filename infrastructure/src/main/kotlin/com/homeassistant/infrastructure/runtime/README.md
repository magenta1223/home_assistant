# Runtime Infrastructure 구현 계획안

## 1. 목적

외부 프로세스의 실행과 종료를 관리하고, 해당 프로세스의 비동기 생명주기 및 상태 전이를 통합 관리하는 범용 Runtime 모듈을 구현한다.

단순한 Process Launcher가 아니라 다음을 담당하는 **Managed Runtime**을 목표로 한다.

- 외부 프로세스 실행 및 종료
- 프로세스의 정상 작동 여부 확인
- 비동기 초기화 및 준비 완료 대기
- 프로세스 비정상 종료 감지
- 상태 전이 및 생명주기 관리
- 시작·종료 타임아웃 처리
- 외부에 현재 상태 및 상태 변경 제공

특정 서비스(Embedding, Reranker, LLM 등)에 종속되지 않는 범용 인프라로 설계한다.

## 2. 설계 원칙

1. **선언적 생명주기 정의**: Runtime의 상태와 전이 규칙을 DSL로 정의한다.
2. **비동기 우선**: Kotlin Coroutine을 기반으로 프로세스 및 생명주기 작업을 처리한다.
3. **상태 전이 일관성**: 상태 변경은 Runtime 내부 엔진만 수행한다.
4. **관심사 분리**: 상태 머신과 실제 OS 프로세스 제어를 분리한다.
5. **구현 기술 은닉**: 외부에는 Runtime 인터페이스만 노출하고, 상태 머신 라이브러리의 API는 노출하지 않는다.
6. **확장성**: 서로 다른 프로세스 및 서비스 준비 조건을 동일한 Runtime 모델로 표현할 수 있어야 한다.

## 3. 아키텍처

다음 구성 요소를 기준으로 설계한다.

### Runtime

외부에 공개하는 인터페이스.

책임:
- Runtime 생명주기 제어
- 현재 상태 조회
- 상태 변경 관찰

예상 API:

- `state: StateFlow<RuntimeState>`
- `create(input)`
- `start()`
- `stop()`
- `destroy()`

실제 메서드 구성은 생명주기 의미를 검토한 뒤 확정한다. 불필요한 API는 추가하지 않는다.

### RuntimeDefinition

Runtime의 상태와 동작을 선언하는 DSL.

책임:
- 초기 상태 정의
- 상태별 진입 및 이탈 동작 정의
- 상태 전이 조건 정의
- 비동기 작업 및 이벤트 처리 정의
- 타임아웃 및 실패 처리 정의

### RuntimeEngine

DSL 정의를 실행하는 엔진.

책임:
- 상태 머신 실행
- 상태 전이 검증 및 처리
- 비동기 작업 관리
- Coroutine 생명주기 관리
- 상태 변경 발행

### ProcessController

실제 OS 프로세스 제어를 담당한다.

책임:
- 프로세스 실행
- 표준 출력 및 표준 에러 처리
- 정상 종료 요청
- 강제 종료
- 프로세스 종료 대기
- 프로세스 종료 감지

RuntimeEngine은 구체적인 OS 프로세스 제어 방식에 의존하지 않는다.

## 4. 상태 모델

기본 상태는 다음과 같이 정의한다.

| 상태 | 의미 |
|---|---|
| NEW | 초기 상태 |
| STARTING | 프로세스 실행 및 서비스 준비 중 |
| RUNNING | 서비스 사용 가능 |
| STOPPING | 프로세스 종료 중 |
| STOPPED | 프로세스 종료 완료 |
| FAILED | 실행 또는 운영 중 오류 발생 |

기본 전이:

```text
NEW
 └─ start → STARTING
               ├─ 준비 완료 → RUNNING
               └─ 실패/타임아웃 → FAILED

RUNNING
 └─ stop → STOPPING
              ├─ 종료 완료 → STOPPED
              └─ 종료 실패 → FAILED

RUNNING
 └─ 프로세스 비정상 종료 → FAILED
```

추후 재시작을 지원할 수 있도록 상태 모델을 확장할 수 있어야 한다.

상태 모델 설계 시 다음을 검토한다.

- STOPPED 상태에서 재시작을 허용할 것인지
- FAILED 상태에서 복구 및 재시작을 허용할 것인지
- DESTROYED를 별도 상태로 둘 필요가 있는지
- 프로세스 생존 상태와 서비스 준비 상태를 분리할 필요가 있는지

특히 프로세스가 살아 있다는 것과 서비스가 요청을 처리할 준비가 됐다는 것은 구분한다.

## 5. Runtime DSL 요구사항

DSL은 Runtime 생명주기를 선언적으로 정의할 수 있어야 한다.

필수 기능:

### 상태 정의
- 초기 상태 지정
- 상태별 진입 동작 (`onEnter`)
- 상태별 이탈 동작 (`onExit`)

### 상태 전이
- 명시적 상태 전이 정의
- 조건 기반 전이
- 이벤트 기반 전이
- 비동기 작업 완료에 따른 전이

### 비동기 처리
- suspend 함수 지원
- 비동기 작업의 취소
- 작업 완료 대기
- 타임아웃
- 예외 발생 시 실패 전이

### 이벤트 처리
- 프로세스 종료 이벤트
- 사용자 종료 요청
- 내부 작업 완료 이벤트
- 오류 이벤트

DSL 사용 예시는 다음과 같다.

```kotlin
runtime<EmbeddingConfig> {
    initialState(NEW)

    state(STARTING) {
        onEnter {
            process = launchProcess(config)
        }

        await {
            process.isAlive() && healthCheck()
        } then RUNNING

        timeout(30.seconds) then FAILED
    }

    state(RUNNING) {
        onEvent<ProcessExited> {
            FAILED
        }

        onEvent<StopRequested> {
            STOPPING
        }
    }

    state(STOPPING) {
        onEnter {
            process.terminate()
            process.awaitExit(5.seconds)
        }

        onTimeout {
            process.kill()
        }

        onComplete {
            STOPPED
        }
    }
}
```

위 코드는 DSL의 목표 형태를 나타내는 예시이며, 실제 문법은 구현 과정에서 적절히 조정한다.

## 6. 상태 머신 라이브러리 검토

Kotlin 상태 머신 라이브러리를 우선 검토한다.

후보:
- KStateMachine
- 직접 구현한 Coroutine 기반 상태 머신

평가 기준:
- 비동기 상태 진입 및 이탈 작업
- 이벤트 기반 상태 전이
- 조건 기반 전이
- 상태 전이의 직렬화 및 동시성 제어
- Coroutine 취소 및 예외 처리
- 타임아웃 지원
- 상태 변경 관찰
- DSL 확장 가능성
- 유지보수성 및 의존성 비용

라이브러리를 도입하더라도 외부 Runtime API에는 라이브러리의 타입을 노출하지 않는다.

단순히 기존 라이브러리를 감싸는 불필요한 추상화는 지양한다.

## 7. 동시성 및 오류 처리

반드시 고려해야 하는 시나리오:

1. STARTING 중 stop 요청
2. 동시에 여러 start 요청
3. 동시에 여러 stop 요청
4. 프로세스 실행 직후 비정상 종료
5. RUNNING 중 프로세스 비정상 종료
6. 준비 완료 대기 타임아웃
7. 정상 종료 실패 후 강제 종료
8. Runtime 종료 중 진행 중인 Coroutine 취소
9. destroy 중 추가 명령 요청

요구사항:
- 상태 전이와 명령 실행의 경쟁 조건 방지
- 프로세스 중복 실행 방지
- 중복 종료 요청에 대한 명확한 동작 정의
- 종료되지 않은 Coroutine 및 리소스 누수 방지
- 예외 발생 시 Runtime 상태의 일관성 보장

특히 `start()`와 `stop()`의 동시 호출 시 동작 정책을 명확히 정의한다.

## 8. 외부 인터페이스 및 관찰

외부에서는 Runtime 구현 세부사항을 몰라도 다음 기능을 사용할 수 있어야 한다.

- Runtime 시작 및 종료
- 현재 상태 조회
- 상태 변경 구독

상태 관찰은 Kotlin `StateFlow`를 사용한다.

구현체 내부에서는 `MutableStateFlow`를 관리하고 외부에는 읽기 전용 `StateFlow`를 제공한다.

상태 변경 이력이나 상세 이벤트 스트림은 실제 사용 사례가 확인된 경우에만 추가한다.

## 9. 구현 단계

### Phase 1. 핵심 모델 설계
- Runtime 인터페이스 정의
- RuntimeState 정의
- 생명주기 명령의 의미 정의
- 상태 전이 규칙 정의

### Phase 2. 상태 머신 검증
- KStateMachine API 및 적합성 검토
- 비동기 작업 및 이벤트 처리 검증
- 동시성 및 취소 동작 검증
- 필요한 경우 직접 구현과 비교

### Phase 3. Runtime Engine 구현
- RuntimeDefinition
- RuntimeEngine
- 상태 전이 관리
- Coroutine 및 StateFlow 통합

### Phase 4. ProcessController 구현
- 프로세스 실행 및 종료
- 종료 감시
- 정상 종료 및 강제 종료
- 표준 출력 및 표준 에러 처리

### Phase 5. DSL 구현
- 상태 선언
- 전이 선언
- 비동기 작업
- 이벤트 및 타임아웃
- 예외 처리

### Phase 6. 실제 Runtime 구현 및 검증
- 간단한 외부 프로세스를 이용한 테스트 Runtime 구현
- Embedding Server 등 실제 사용 사례 적용
- 동시성 및 비정상 종료 테스트
- DSL의 불필요한 복잡성 제거

## 10. 테스트 및 완료 기준

다음 항목을 검증해야 한다.

- 정상적인 시작 및 종료
- 서비스 준비 완료 확인
- 시작 실패 및 타임아웃
- 비정상 프로세스 종료 감지
- 종료 타임아웃 및 강제 종료
- 동시 start/stop 호출
- Coroutine 취소 및 리소스 정리
- 외부 StateFlow 구독 및 상태 변경 전달
- 여러 종류의 Runtime 구현 가능 여부

최종적으로 다음 조건을 만족해야 한다.

1. Runtime 구현자가 생명주기 관리 코드를 매번 직접 작성하지 않아도 된다.
2. Runtime별 차이는 프로세스 실행 방식과 준비 완료 조건을 중심으로 정의할 수 있다.
3. 상태 전이와 비동기 작업의 실행 흐름이 DSL 정의에서 명확하게 드러난다.
4. 외부에서는 간결한 Runtime 인터페이스만 사용한다.
5. Runtime 구현에 불필요한 프레임워크 복잡성이 추가되지 않는다.

## 11. 범위에서 제외할 사항

초기 구현에서는 다음 기능을 필수 범위에 포함하지 않는다.

- 다중 Runtime의 전체 오케스트레이션
- 자동 재시작 및 재시도 정책
- 프로세스 간 통신 프로토콜 추상화
- 분산 프로세스 관리
- 복잡한 의존성 그래프 및 서비스 디스커버리

실제 사용 사례가 발생하면 별도 확장한다.

---

**핵심 목표:** 외부 프로세스의 실행·종료와 비동기 생명주기를 공통 Runtime Engine으로 관리하고, 개별 Runtime은 DSL을 통해 자신의 생명주기를 선언하도록 설계한다.