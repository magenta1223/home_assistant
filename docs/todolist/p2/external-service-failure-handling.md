# 외부 서비스 장애 처리 표준화

- 상태: TODO
- 우선순위: P2
- 선행 작업: [Topic 할당 outbox와 worker](../p1/topic-assignment-outbox.md)

## 문제

Topic 할당 worker는 라운드 전에 `isReady()`(Ollama 준비 + CLI 실행 가능)로 사전 확인만 한다. 그래서 두 경우를 놓친다.

- 확인 직후 서비스가 꺼지면 claim한 작업이 실패로 기록되고 시도 횟수 1회를 잃는다.
- CLI는 실행되지만 로그인·네트워크 문제로 호출이 실패하면, 확인을 통과한 뒤 시도 횟수를 소모해 결국 영구 FAILED가 된다.

장애로 인한 실패와 Memory 내용으로 인한 실패를 구분하지 못하는 것이 원인이다. 같은 문제는 indexing outbox와 앞으로의 Memory 분석 outbox에도 생긴다.

## 조사한 표준 절차

| 절차 | 내용 | 출처 |
|---|---|---|
| 실패 분류 | 일시적(transient: 연결 거부, timeout, 5xx, 과부하)과 영구적(입력·검증 오류) 실패를 나눈다. 일시적 실패만 재시도하고 영구 실패는 즉시 격리한다. | [Azure: Handle transient faults](https://learn.microsoft.com/azure/well-architected/design-guides/handle-transient-faults) |
| 재시도 + backoff | 일시적 실패는 지수 backoff와 jitter로 제한된 횟수만 재시도한다. | [Azure: Retry pattern](https://learn.microsoft.com/azure/architecture/patterns/retry), [AWS: Timeouts, retries and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) |
| Circuit breaker | 의존 서비스별로 실패가 누적되면 Open으로 바꿔 호출 자체를 막고, 일정 시간 뒤 Half-open에서 시험 호출로 복구를 확인한다. 장애 중 호출 낭비와 재시도 폭주를 막는다. | [Azure: Circuit Breaker pattern](https://learn.microsoft.com/azure/architecture/patterns/circuit-breaker), [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) |
| Health check | 의존 서비스의 상태를 별도 endpoint·probe로 확인한다. 사전 확인은 이 방식에 해당하며, 호출 성공을 보장하지는 않는다. | [Azure: Health Endpoint Monitoring](https://learn.microsoft.com/azure/architecture/patterns/health-endpoint-monitoring) |
| Poison message와 DLQ | 메시지마다 전달 횟수를 세고, 상한(`maxReceiveCount`, `MaxDeliveryCount`)을 넘으면 dead-letter queue로 옮겨 사람이 확인한다. | [AWS SQS dead-letter queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html), [Azure Service Bus dead-letter queues](https://learn.microsoft.com/azure/service-bus-messaging/service-bus-dead-letter-queues) |
| 장애 중 소비 중지 | 브로커는 수신할 때마다 전달 횟수를 올리므로, 장애 중 계속 수신하면 정상 메시지까지 DLQ로 간다. 그래서 consumer 쪽 circuit breaker가 Open이면 수신 자체를 멈춘다. | [Azure SDK blog: exponential backoff and circuit breaker in Azure Functions](https://devblogs.microsoft.com/azure-sdk/exponential-backoff-circuit-breaker-azure-functions/) |

정리하면 표준 조합은 "실패 분류 + backoff 재시도 + 의존 서비스별 circuit breaker + 전달 횟수 상한과 DLQ"다.
사전 확인만으로는 부족하고, 실행 중 일시적 실패가 circuit을 열어 소비를 멈추게 하는 것이 핵심이다.

## 현재 구현과의 대응

- 시도 횟수 상한(5회)과 FAILED 상태: 전달 횟수 상한과 DLQ에 해당한다. FAILED 행을 보고 되살리는 운영 경로는 없다.
- 라운드 전 `isReady()`: health check에 해당한다. circuit breaker의 Half-open 시험 호출과 비슷하지만, 실행 중 실패를 반영하지 않는다.
- 실패 분류 없음: 모든 예외가 시도 1회를 소모한다.

## 검토할 것

- 실패 분류 위치: 모델 응답 decode·검증 실패만 영구 실패로 보고(application 예외), 나머지는 일시적 실패로 볼지 정한다. 어댑터가 연결·프로세스 오류를 일시적 실패 예외로 바꾸는 방식과 비교한다.
- 일시적 실패 처리: 시도 횟수를 소모하지 않고 claim을 되돌리는 outbox 연산이 필요하다. 현재 attempts가 claim fencing 세대 번호를 겸하므로 둘을 분리해야 한다.
- Circuit breaker: Ollama와 CLI 각각에 두고, Open이면 worker 라운드를 건너뛴다. 그러면 `isReady()` 사전 확인을 대체하거나 Half-open 시험으로 흡수할 수 있다. Resilience4j 도입과 직접 구현(상태 3개)의 비용을 비교한다.
- 적용 범위: Topic 할당, indexing outbox, Memory 분석 outbox에 공통 적용할 수 있는 경계(adapter 또는 application port)를 정한다.
- FAILED 행 조회와 재처리 경로(DLQ 운영)를 둘지 정한다.
