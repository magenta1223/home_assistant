# BanksaladMailFetcher

가족 구성원이 뱅크샐러드 앱의 "데이터 내보내기 → 파일로 받기"로 한 Gmail 주소에 보낸 내보내기를
앱의 금융 주입 API(`POST /api/finance/import`)로 보낸다. 거래는 구성원 각자의 것으로 저장된다.
내보내기는 사람이 앱에서 직접 누른다. 뱅크샐러드에는 정기 발송 기능이 없다.

## 준비

- Python 3 표준 라이브러리만 쓴다.
- 받는 Gmail 계정에 2단계 인증을 켜고 [앱 비밀번호](https://myaccount.google.com/apppasswords)를 하나 만든다.
- 구성원은 내보낼 때 받는 주소로 그 Gmail 주소를 넣는다.
- 설정은 프로세스 환경변수가 먼저, 없으면 저장소 루트 `.env`.
  - `BANKSALAD_MAIL_IMAP_USER`: 받는 Gmail 주소
  - `BANKSALAD_MAIL_IMAP_APP_PASSWORD`: 그 계정의 앱 비밀번호
  - `BANKSALAD_MAIL_SENDER`: 내보내기 메일의 발신 주소, `export-noreply@banksalad.com`
  - `BANKSALAD_FETCHER_MEMBERS_JSON`: 구성원 목록. 항목마다 `name`(뱅크샐러드가 첨부 이름 `<name>님_`에 쓰는 이름),
    `exportPassword`(그 구성원이 내보낼 때 정한 비밀번호), `apiToken`(그 구성원의 앱 HTTP 토큰. 이 토큰의 사용자가 거래의 소유자가 된다).
    가족 중 이름이 같은 사람은 없다고 전제한다.
  - `BANKSALAD_IMPORT_URL`: 기본 `http://127.0.0.1:8080`

```text
BANKSALAD_FETCHER_MEMBERS_JSON=[{"name":"김동훈","exportPassword":"...","apiToken":"..."}]
```

## 실행

```bash
py -3 tools/banksalad-mail-fetcher/banksalad_mail_fetcher.py
```

1. 받은편지함을 읽기 전용으로 열고, `BANKSALAD_MAIL_SENDER`가 최근 7일 동안 보낸 메일을 찾는다. 메일은 읽음으로 바뀌지 않는다.
2. 첨부 `<name>님_<기간>.zip`의 이름으로 구성원을 찾고, 그 구성원의 `exportPassword`로 풀어 안의 xlsx를 그 구성원의 `apiToken`으로 보낸다.
3. 앱이 `200`으로 받으면 `runtime/banksalad/sent/<메일 Date 헤더 시각 yyyyMMdd-HHmmss>_<zip 이름>.xlsx`에 기록을 남긴다.
   기록이 있는 내보내기는 다시 보내지 않는다. 받지 않은 내보내기는 다음 실행에서 다시 보낸다.
   - 목록에 없는 이름의 메일: 경고만 남기고 건너뛴다.
   - 비밀번호 오류, `200`이 아닌 응답, 앱 연결 실패: 그 메일의 실패로 남기고 다른 메일은 계속한다.

종료 코드: `0` 전부 성공(새 내보내기 없음 포함), `1` 일부 메일 실패, `2` 실행 불가(설정 누락, IMAP 로그인 실패).

## 테스트

```bash
cd tools/banksalad-mail-fetcher && py -3 -m unittest test_banksalad_mail_fetcher test_finance_import
```

## 작업 스케줄러 등록 (직접 등록)

```bash
schtasks /Create /TN "HomeServers\BanksaladMailFetcher" /SC DAILY /ST 09:00 /RL LIMITED /TR "py -3 C:\homeServers\tools\banksalad-mail-fetcher\banksalad_mail_fetcher.py"
```

- 해제: `schtasks /Delete /TN "HomeServers\BanksaladMailFetcher"`
