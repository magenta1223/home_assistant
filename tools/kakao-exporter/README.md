# KakaoExporter

카카오톡 PC의 공식 "대화 내용 저장"(채팅방 창에서 `Ctrl+S`)을 허용한 채팅방마다 자동으로 실행해
`runtime/kakao/exports/<chatKey>.txt`를 만든다. 앱의 `KakaoExportFolderStore`가 이 파일을 읽는다.
카카오톡 창만 조작하며, 로컬 DB 파일·프로세스 메모리는 읽지 않는다.

## 준비

```bash
py -3 -m pip install --user pywinauto
```

- 카카오톡 PC에 로그인해 메인 창을 띄워 둔다.
- 설정은 앱과 같다. 프로세스 환경변수가 먼저, 없으면 저장소 루트 `.env`.
  - `KAKAO_LOCAL_DB_CHATS_JSON`: `chatKey`(파일 이름)와 `roomName`(카카오톡 채팅방 이름)
  - `KAKAO_EXPORT_DIR`: 기본 `runtime/kakao/exports`

## 실행

```bash
py -3 tools/kakao-exporter/kakao_exporter.py
```

```bash
py -3 tools/kakao-exporter/kakao_exporter.py --only self
```

채팅방마다 다음을 한다.

1. 채팅 목록에서 `Ctrl+F`로 `roomName`을 검색하고 첫 결과를 연다. 카카오톡은 검색 결과를 직접 그려서
   읽을 수 없으므로, 열린 창 제목이 `roomName`과 정확히 같은 창이 하나일 때만 진행한다. 아니면 잘못 열린
   창을 닫고 그 방은 건너뛰며 오류를 남긴다. 방 이름은 고유하고 바꾸지 않는다고 전제한다
   (같은 이름의 방 둘은 구분하지 못한다).
2. 채팅방 창에서 `Ctrl+S` → 저장 대화상자에 `<chatKey>.tmp.txt` 경로를 넣고 저장한다.
3. 채팅방 창을 닫고, UTF-8이 아니면 UTF-8로 바꾼 뒤 `<chatKey>.txt`로 원자적으로 교체한다.

종료 코드: `0` 전부 성공, `1` 일부 방 실패, `2` 실행 불가(Windows 화면 잠김, 카카오톡 잠금모드,
카카오톡 미실행). 화면이 잠겨 있으면 UI 자동화가 창에 닿지 않으므로 시작하자마자 실패한다.

## 작업 스케줄러 등록 (직접 등록)

이 스크립트는 로그온한 대화형 세션에서만 동작한다. "사용자가 로그온할 때만 실행"으로 등록한다.

```bash
schtasks /Create /TN "HomeServers\KakaoExporter" /SC MINUTE /MO 10 /RL LIMITED /IT /TR "py -3 C:\homeServers\tools\kakao-exporter\kakao_exporter.py"
```

- `/IT`: 사용자가 로그온해 있을 때만 실행한다. 잠금 중 실행은 종료 코드 `2`로 끝난다.
- 실행하는 동안 카카오톡 창이 앞으로 나오고 키 입력을 보낸다. 사용 중인 시간대는 피한다.
- 해제: `schtasks /Delete /TN "HomeServers\KakaoExporter"`
