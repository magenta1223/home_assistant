# BanksaladMailFetcher

뱅크샐러드 앱의 "데이터 내보내기 → 파일로 받기"가 보낸 메일에서 비밀번호 걸린 zip 첨부를 풀어 안의 xlsx를 `BANKSALAD_EXPORT_DIR`에 놓는다.
앱의 폴더 동기화가 그 폴더를 폴링해 가져온다. 이 스크립트는 앱 API를 부르지 않고 xlsx를 열지 않는다.
내보내기는 사람이 앱에서 직접 누른다. 뱅크샐러드에는 정기 발송 기능이 없다.

## 준비

- Python 3 표준 라이브러리만 쓴다.
- Gmail 계정에 2단계 인증을 켜고 [앱 비밀번호](https://myaccount.google.com/apppasswords)를 하나 만든다.
- 설정은 프로세스 환경변수가 먼저, 없으면 저장소 루트 `.env`.
  - `BANKSALAD_EXPORT_DIR`: xlsx를 놓을 폴더. 앱과 같은 키를 쓴다.
  - `BANKSALAD_MAIL_IMAP_USER`: Gmail 주소
  - `BANKSALAD_MAIL_IMAP_APP_PASSWORD`: 앱 비밀번호
  - `BANKSALAD_MAIL_SENDER`: 내보내기 메일의 발신 주소, `export-noreply@banksalad.com`
  - `BANKSALAD_EXPORT_PASSWORD`: 앱에서 내보낼 때 정한 엑셀 파일 비밀번호(zip에 걸린다)

## 실행

```bash
py -3 tools/banksalad-mail-fetcher/banksalad_mail_fetcher.py
```

1. 받은편지함을 읽기 전용으로 열고, `BANKSALAD_MAIL_SENDER`가 최근 7일 동안 보낸 메일을 찾는다. 메일은 읽음으로 바뀌지 않는다.
2. 메일마다 `.zip` 첨부를 `BANKSALAD_EXPORT_PASSWORD`로 풀고, 안의 xlsx를 `<메일 Date 헤더 시각 yyyyMMdd-HHmmss>_<xlsx 이름>`으로 저장한다.
   같은 이름이 이미 있으면 건너뛴다.
   임시 파일에 쓴 뒤 이름을 바꾸므로 앱이 쓰는 중인 파일을 읽지 않는다.
3. 안의 xlsx에는 비밀번호가 없으므로 앱은 복호화하지 않는다.

종료 코드: `0` 전부 성공(새 파일 없음 포함), `1` 일부 메일 저장 실패(비밀번호 오류 포함), `2` 실행 불가(설정 누락, IMAP 로그인 실패).

## 테스트

```bash
cd tools/banksalad-mail-fetcher && py -3 -m unittest test_banksalad_mail_fetcher
```

## 작업 스케줄러 등록 (직접 등록)

```bash
schtasks /Create /TN "HomeServers\BanksaladMailFetcher" /SC DAILY /ST 09:00 /RL LIMITED /TR "py -3 C:\homeServers\tools\banksalad-mail-fetcher\banksalad_mail_fetcher.py"
```

- 해제: `schtasks /Delete /TN "HomeServers\BanksaladMailFetcher"`
