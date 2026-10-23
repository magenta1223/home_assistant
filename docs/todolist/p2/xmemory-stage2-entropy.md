# xMemory Stage II: entropy 기반 Memory 추가

- 상태: HOLD — proxy 모델의 지연과 효과를 확인한 뒤 결정
- 우선순위: P2
- 선행 작업: [Topic 검색](../../../application/src/main/kotlin/com/homeassistant/application/usecase/topic/README.md#topic-검색)

## 문제

xMemory Stage II(식 5)는 backbone component의 하위 텍스트를 reader의 다음 토큰 entropy가 줄어들 때만 context에 더한다.
우리 검색은 고른 Topic의 Memory를 질문 관련도 순으로 8,000자 안에서 싣는다. 관련은 있어도 답에 보탬이 없는 Memory가 예산을 쓴다.

## 제약

- 대화 reader(agent backend: Claude, Codex CLI)는 logit을 주지 않는다. 논문도 black-box reader에는 logit을 주는 proxy 모델을 쓴다(App. A.3).
- proxy 후보는 이미 쓰는 Ollama다. 지금은 embedding 모델만 띄우므로 생성 모델을 추가로 받아야 한다.
- greedy로 Memory 하나를 판정할 때마다 지금까지 쌓은 context로 proxy 추론을 한 번 한다. 후보 Memory가 50개면 질문당 최대 50번이다.
- proxy의 entropy가 실제 reader의 불확실성을 대신한다는 보장은 없다.

## 시작 조건

- 생성 모델 하나를 정하고 질문당 지연을 측정한다.
- 같은 질문 묶음에서 현재 방식과 답변 품질을 비교해 효과가 있을 때 도입한다.
