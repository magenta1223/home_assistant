# 금융 정보를 대화 context로 제공

- 상태: HOLD
- 우선순위: Feature P1
- 선행 작업: 없음

## 목적

채팅 답변이 Episode·Component 외에 금융 정보도 참고하도록 `ContextProvider`를 하나 더 둔다.
`ContextAssembler`가 provider 목록을 조합하므로, 새 provider 클래스와 wiring 한 줄을 추가하는 구조다.

## 보류 사유

2026-11-08 사용자 요청으로 중단했다. 설계 전에 아래를 정해야 한다.

1. 대상 API: 은행 계좌(오픈뱅킹·마이데이터), 증권 잔고, 공개 시세(주식·코인) 중 무엇인가.
2. 인증 방식(API key, OAuth)과 키 보관 위치.
3. 답변에 넣을 데이터: 잔고, 거래 내역, 보유 종목 시세 등.
4. 접근 범위: 사용자별 데이터인지 가구 공용인지, 누가 무엇을 볼 수 있는지.

## 완료 조건

- 위 결정 후 AS-IS/TO-BE 설계안과 sequence diagram으로 결정받는다.
- 외부 API는 outbound port 뒤에 두고, 장애 시 다른 provider의 답변을 막지 않는다.
