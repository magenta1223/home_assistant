# Runtime

앱과 수명이 같은 구성요소(관리 프로세스, scheduled worker 등)의 시작·종료와 상태를 다룬다. 상태 의미는 Guava `Service`를 따른다.

## 구성

- `Runtime`: 외부에 공개하는 interface. `state`, `start()`, `stop()`.
- `RuntimeState`: `NEW`, `STARTING`, `RUNNING`, `STOPPING`, `TERMINATED`, `FAILED`.
- `AbstractRuntime`: 상태 전이를 맡는다. 구현은 `onStart()`, `onStop()`을 채우고, 실행 중 자원이 스스로 죽으면 `fail(cause)`를 부른다.

## 상태 전이

| 현재 | 호출·사건 | 다음 |
|---|---|---|
| NEW | `start()` | STARTING → `onStart()` 성공 시 RUNNING, 예외 시 FAILED |
| NEW | `stop()` | TERMINATED (`onStop()` 부르지 않음) |
| RUNNING | `stop()` | STOPPING → `onStop()` 성공 시 TERMINATED, 예외 시 FAILED |
| STARTING, RUNNING | `fail(cause)` | FAILED |
| STARTING | `stop()` | `start()`가 끝날 때까지 기다린 뒤 위 규칙 적용 |
| STOPPING, TERMINATED, FAILED | `stop()` | 그대로 |
| NEW 외 | `start()` | `IllegalStateException` |

- 한 번 시작하고 한 번 멈춘다. 다시 띄우려면 새 인스턴스를 만든다.
- `onStart()`가 실패하면 `onStop()`은 불리지 않는다. 일부만 연 자원은 `onStart()`가 정리한다.
- `start()`와 `stop()`은 같은 lock으로 직렬화되고, `fail()`은 lock을 잡지 않는다.

## 범위 밖

chat session처럼 사용자별로 생기고 DB에 영속되는 상태는 다루지 않는다. 그런 상태 전이는 domain·application에 둔다.
