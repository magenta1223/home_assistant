# 카카오톡 내보내기 전용 기기 운영

- 상태: TODO
- 우선순위: P2
- 선행 작업: Kakao exporter의 HTTP 주입 (`tools/kakao-exporter/`)

## 문제

`tools/kakao-exporter`는 카카오톡 PC 창을 앞으로 가져와 키 입력을 보낸다(검색 → 방 열기 → `Ctrl+S` 저장).
그래서 사용자가 쓰는 PC에서 돌리면 실행 중 사용자 작업과 충돌하고, Windows 화면이 잠기면 UI 자동화가
창에 닿지 않아 실패한다(종료 코드 `2`).

같은 PC 안에서 격리하는 방법은 맞지 않는다.

- Windows 11 Home이라 Hyper-V와 Windows Sandbox가 없다.
- 두 번째 사용자 세션을 동시에 켜 둘 수 없다.
- 숨긴 데스크톱·비활성 세션은 키 입력을 받지 못한다.
- VirtualBox VM은 가능하지만 VM용 Windows 라이선스와 RAM 4GB 정도가 든다.

결정(2026-10-21): 사용자가 쓰지 않는 별도 Windows 기기(미니 PC 또는 구형 노트북, Home 가능)에서
내보내기를 실행한다. exporter는 저장한 파일을 앱의 HTTP 주입 API로 보내므로 코드 변경 없이 쓴다.

제약: 카카오톡 PC는 동시에 PC 한 대만 로그인되는 것으로 알려져 있다(미확인). 그러면 그 기기가 유일한
PC 카카오톡이 되고 기존 PC의 카카오톡 PC는 로그아웃된다.

## 목표

- 사용자 PC 사용과 화면 잠금에 상관없이 허용 채팅방 export가 주기적으로 갱신된다.
- 앱은 수동 주입과 같은 HTTP 입구로 받는다.

## 구현 계획

1. 기기 준비: Windows 자동 로그인, 화면 잠금·화면 보호기·절전 끄기, 상시 전원.
2. 카카오톡 PC 설치·로그인, 카카오톡 잠금모드 끄기. PC 한 대 로그인 제약을 실제로 확인한다.
3. Python 3.12 + pywinauto 설치, 저장소 클론, `tools/kakao-exporter/README.md`대로 작업 스케줄러에
   "사용자가 로그온할 때만 실행"으로 등록한다.
4. 앱이 다른 기기에 있으면 `KAKAO_IMPORT_URL`을 그 앱의 tailnet 주소로 지정한다.
5. `.env`에 `KAKAO_EXPORTER_CHATS_JSON`과 `KAKAO_EXPORTER_API_TOKEN`을 설정한다.
6. 방 하나로 end-to-end를 확인한 뒤 허용 방을 늘린다.

## 완료 조건

- 스케줄 실행이 사람 개입 없이 하루 이상 연속 성공한다.
- 앱이 새 메시지를 수집한다(exporter 출력의 `analyzed`).
- 기존 PC 작업과 충돌하지 않는다.

## 제외 범위

- 이미지 수집.
- Linux·macOS 이식. 카카오톡 Linux 클라이언트가 없고, macOS는 Apple 하드웨어와 새 exporter가 필요하다.
- 알림 기반 실시간 수집(Android NotificationListener + adb)은 별도로 검토한다. 같은 계정이면 내가 보낸
  메시지는 알림이 없어 빠지고, 집 전용 계정이면 해결되지만 방에 구성원으로 보인다.
