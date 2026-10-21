# entropy 기반 Group 포화 판단

- 상태: TODO
- 우선순위: P2
- 선행 작업: Memory → Topic → Group 배정 (2026-10-19)

## 문제

현재 Group 분할 검토는 크기 상한 12개로만 시작된다. 멤버 수가 적어도 의미가 여러 방향으로 퍼진 Group은
분할 검토 대상이 되지 않고, 반대로 응집된 Group도 12개를 넘으면 분할 검토 대상이 된다.

## 목표

Group이 의미적으로 포화됐는지 entropy로 판단해 분할 검토 시점을 정한다. 분할 후보 선택은 기존
`SparsityScore + SemScore`를 그대로 사용하고, 바뀌는 것은 "언제 분할을 검토할지"뿐이다.

## 후보 방식

1. 멤버 임베딩 공분산 고유값의 spectral entropy(effective rank). 임베딩만으로 계산하므로 LLM 호출이 없다.
   임계값을 넘으면 분할 후보로 올린다.
2. Group 요약의 Self-Sufficiency: SS = 1 − H(G|ρ(G)) / H(G). Group 요약이 생긴 뒤에만 의미가 있고 측정에 LLM 호출이 필요하다.

1번부터 검토한다. 크기 상한을 대체할지 병행할지, 임계값을 어떻게 정할지는 구현 전에 실제 Group 분포로 정한다.

## 참고

- xMemory v4에서 entropy는 Group 할당이 아니라 검색 Stage II 원문 확장(§3.2 식 5, Appendix A.3)에만 쓰인다.
  Group 조직은 §3.1 식 1~3뿐이다.
- [Talebirad 외 2026, Toward a Theory of Hierarchical Memory for Language Agents](https://arxiv.org/html/2603.21564) §2.3 식 1 Self-Sufficiency. 분석 지표로만 쓰고 분할 트리거로는 쓰지 않는다.
- [Cobweb (Lian·Varma·MacLellan 2024)](https://arxiv.org/pdf/2403.03835): entropy 기반 category utility로 add/new/merge/split 선택. 이산 속성 기준.
