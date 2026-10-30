# KakaoExporter

카카오톡 PC의 공식 "대화 내용 저장"(채팅방 창에서 `Ctrl+S`)을 허용한 채팅방마다 자동으로 실행하고,
저장한 파일 전체를 앱의 지식 주입 API(`POST /api/knowledge/import/analyze`, `sourceType` `KAKAO`)로 보낸다.
수동 주입과 같은 입구라서 인증·공개 범위·검증이 같다. 앱은 이미 가진 메시지는 건너뛰고 새 메시지만 분석한다.
카카오톡 창만 조작하며, 로컬 DB 파일·프로세스 메모리는 읽지 않는다.

## 준비

```bash
py -3 -m pip install --user pywinauto
```

- 카카오톡 PC에 로그인해 메인 창을 띄워 둔다.
- 설정은 프로세스 환경변수가 먼저, 없으면 저장소 루트 `.env`.
  - `KAKAO_EXPORTER_CHATS_JSON`: 채팅방 목록. 항목마다 `chatKey`(파일 이름, `[A-Za-z0-9_-]`),
    `roomName`(카카오톡 채팅방 이름), `sourceName`(앱의 source 이름), `isPublic`, `allowedUserIds`.
    공개 범위는 source마다 처음 저장한 값으로 고정되므로 바꾸지 않는다.
  - `KAKAO_EXPORTER_API_TOKEN`: 앱의 HTTP 토큰. 이 토큰의 사용자가 주입한 사람으로 기록된다.
  - `KAKAO_IMPORT_URL`: 기본 `http://127.0.0.1:8080`

```text
KAKAO_EXPORTER_CHATS_JSON=[{"chatKey":"hongseungmin","roomName":"홍승민","sourceName":"hongseungmin","isPublic":false,"allowedUserIds":["member-..."]}]
```

## 실행

```bash
py -3 tools/kakao-exporter/kakao_exporter.py
```

```bash
py -3 tools/kakao-exporter/kakao_exporter.py --only hongseungmin
```

채팅방마다 다음을 한다.

1. 채팅 목록에서 `Ctrl+F`로 `roomName`을 검색하고 첫 결과를 연다. 카카오톡은 검색 결과를 직접 그려서
   읽을 수 없으므로, 열린 창 제목이 `roomName`과 정확히 같은 창이 하나일 때만 진행한다. 아니면 잘못 열린
   창을 닫고 그 방은 건너뛰며 오류를 남긴다. 방 이름은 고유하고 바꾸지 않는다고 전제한다
   (같은 이름의 방 둘은 구분하지 못한다).
2. 채팅방 창에서 `Ctrl+S` → 저장 대화상자에 `<chatKey>.tmp.txt` 경로를 넣고 저장한다.
3. 채팅방 창을 닫고, UTF-8이 아니면 UTF-8로 바꾼 뒤 `runtime/kakao/exports/<chatKey>.txt`로 원자적으로 교체한다.
4. 파일 전체를 주입 API로 보낸다. 앱은 끝까지 분석한 뒤 응답하므로 요청 타임아웃은 30분이다.
   - `200`: 새 메시지를 분석했다.
   - `409`이고 `alreadyAnalyzedRecordCount`가 있음: 새 메시지가 없다. 성공으로 본다.
   - 그 밖의 응답(공개 범위가 저장된 값과 다른 `409` 포함)과 연결 실패: 그 방의 실패로 남긴다.

종료 코드: `0` 전부 성공, `1` 일부 방 실패, `2` 실행 불가(Windows 화면 잠김, 카카오톡 잠금모드,
카카오톡 미실행). 화면이 잠겨 있으면 UI 자동화가 창에 닿지 않으므로 시작하자마자 실패한다.

## 테스트

```bash
cd tools/kakao-exporter && py -3 -m unittest test_knowledge_import
```

## 작업 스케줄러 등록 (직접 등록)

이 스크립트는 로그온한 대화형 세션에서만 동작한다. "사용자가 로그온할 때만 실행"으로 등록한다.

```bash
schtasks /Create /TN "HomeServers\KakaoExporter" /SC MINUTE /MO 10 /RL LIMITED /IT /TR "py -3 C:\homeServers\tools\kakao-exporter\kakao_exporter.py"
```

- `/IT`: 사용자가 로그온해 있을 때만 실행한다. 잠금 중 실행은 종료 코드 `2`로 끝난다.
- 실행하는 동안 카카오톡 창이 앞으로 나오고 키 입력을 보낸다. 사용 중인 시간대는 피한다.
- 해제: `schtasks /Delete /TN "HomeServers\KakaoExporter"`
