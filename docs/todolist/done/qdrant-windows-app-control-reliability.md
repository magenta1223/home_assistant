# Windows App Control 환경의 Qdrant 기동 신뢰성

- 상태: 완료 (운영 수용)
- 우선순위: P0
- 완료일: 2026-08-30
- 선행 작업: 없음

## 완료 결과

프로젝트가 관리하는 Qdrant의 초기화와 production server 원격 기동이 정상적으로 동작하는 것을
실사용 과정에서 확인했다. 현재 서버 가용성을 막는 문제가 재현되지 않으므로 운영 P0를 종료한다.

아래의 정책 증거 수집, 서명된 배포물 검토와 진단 강화 계획은 이번 종료에서 구현된 것으로
간주하지 않는다. 과거 오류 4551을 발생시킨 Windows App Control 정책과 판정 경로도 확정되지
않았다. 같은 오류가 다시 발생하면 storage를 보존하고 당시 정책·Code Integrity event·실행 파일
hash와 서명 상태를 먼저 수집하는 새로운 incident 작업으로 재개한다.

## 문제

프로젝트가 관리하는 Qdrant Windows 실행 파일이 이전에는 정상 실행됐지만, 같은 파일의 서버
재시작 과정에서 Windows가 프로세스를 만들기 전에 오류 4551
(`ERROR_SYSTEM_INTEGRITY_POLICY_VIOLATION`)로 차단했다. Qdrant가 시작되지 않으므로 애플리케이션
전체가 기동하지 못하는 운영 장애다.

확인된 타임라인은 다음과 같다.

1. 2026-08-24 04:00: Qdrant 정상 실행
2. 2026-08-24 19:23: Qdrant 파일에서 Windows 실행 신뢰 캐시
   (`$KERNEL.PURGE.ESBCACHE`)가 제거됨
3. 2026-08-24 22:09: 서버 재시작 중 Qdrant 프로세스 생성이 오류 4551로 거부됨
4. 이후 같은 파일의 단순 재실행도 동일하게 거부됨

파일 변경이나 배포 손상은 원인이 아니다.

- 로컬 `qdrant.exe` SHA-256:
  `369C562EAE3D89333A13ABFDB522FA209E3F587C1217A1059D817E80814EA9D4`
- 공식 Qdrant v1.19.0 Windows ZIP을 다시 받아 추출한 실행 파일과 SHA-256 및 크기가 일치한다.
- 공식 ZIP SHA-256도 설치 manifest의
  `980CB2E1AE771155CF211DA8C0A8A9206B6482BD4EFFDC4DB994D3ADB707B087`과 일치한다.
- Qdrant 실행 파일은 Authenticode 서명이 없는 `NotSigned` 상태다.
- 정상 애플리케이션 시작은 이미 설치된 실행 파일을 다시 다운로드하거나 수정하지 않고 그 경로를
  `ProcessBuilder`에 전달한다.

Windows App Control은 통과한 파일의 판정을 NTFS kernel extended attribute에 캐시해 재사용할 수
있다. 캐시가 만료 또는 무효화된 뒤에는 같은 바이트의 파일도 다시 정책 및 평판 평가를 받는다.
이미 실행 중인 프로세스는 계속 동작할 수 있지만, 다음 프로세스 생성부터 새 판정이 적용되므로
재시작 시점에 잠복 장애가 드러날 수 있다.

다만 현재 증거만으로 다음 내용까지 확정해서는 안 된다.

- 19:23은 과거 캐시가 무효화된 시점이지 Microsoft 클라우드 평판이 변경된 시점은 아니다.
- 오류 4551은 Windows App Control/Code Integrity 계층의 거부를 증명하지만, 차단 정책이 반드시
  Smart App Control이었다는 뜻은 아니다.
- 현재 시스템은 `VerifiedAndReputablePolicyState = 0`, 사용자 모드 Code Integrity 강제 상태도
  0이며, 보존된 로그에 Qdrant 관련 3077 이벤트와 `VerifiedAndReputableDesktop` 활성화 기록이
  없다. 장애 이후 정책 상태를 바꾸지 않았다면 기존 SAC 원인 가설과 모순된다.
- 새 판정은 악성 판정일 수도 있지만, unsigned 파일에 대해 안전하다는 확신을 얻지 못한
  `unknown/untrusted` 판정일 가능성도 있다.

Smart App Control에는 개별 파일 허용 예외가 없다. 파일 해시 또는 서명자 허용 규칙은 별도의
App Control for Business 정책에서 구성해야 하며, 현재 Windows Home/Core 환경은 그 운영 방식을
지원 대상으로 전제할 수 없다. 따라서 "SAC를 유지하며 이 Qdrant 파일 하나만 허용"을 복구안으로
간주하지 않는다.

## 목표

- Qdrant 차단을 발생시킨 당시의 실제 App Control 정책과 판정 경로를 증거로 식별한다.
- 서버를 복구하되 Qdrant 실행 여부를 변동 가능한 로컬 평판 캐시에만 의존하지 않게 한다.
- 향후 같은 오류가 발생하면 실행 파일, 서명, hash, 정책 오류를 즉시 구분할 수 있는 진단을
  제공한다.
- Qdrant storage를 보존하며 복구 및 runtime 교체 절차를 운영 문서로 고정한다.

## 구현 계획

1. 정책을 새로 고치거나 시스템을 재부팅하기 전에 관리자 권한으로 `CiTool.exe -lp -json`을
   수집해 실제 적용 중인 정책의 이름, GUID, enforcement 상태를 확인한다.
2. 통제된 재현 한 번과 같은 시각의 Code Integrity Operational 로그를 수집한다.
   - 3077 차단 이벤트의 `PolicyGUID`, `RequestedSigningLevel`, `ValidatedSigningLevel`
   - 연관된 3089 서명 검증 이벤트
   - Smart App Control 알림 및 당시 `VerifiedAndReputablePolicyState`
3. 수집 결과로 다음 원인을 구분한다.
   - Smart App Control enforcement의 unsigned/unknown 차단
   - 별도 App Control 정책의 명시적 또는 암시적 deny
   - 정책 해제 후 남은 runtime policy나 불완전한 정책 제거
   - 오류가 발생한 다른 실행 환경 또는 프로세스 경계
4. Qdrant storage를 별도로 백업·검증한 뒤 즉시 복구 경로를 선택한다.
   - 우선: 공인 Authenticode 서명이 포함된 공식 또는 검증 가능한 Qdrant 배포물
   - 차선: 공인 code-signing 인증서로 서명한 재현 가능한 사내 빌드
   - 정책 관리가 필요하면: 지원되는 Windows edition에서 ISG와 명시적 hash/서명자 allow rule을
     포함한 자체 App Control for Business 정책
   - 긴급 임시 조치: 현재 Windows가 지원하는 SAC 비활성화/재활성화 절차를 사용하되 장기
     해결책으로 간주하지 않는다.
5. `setupQdrant`가 설치 완료 시 archive hash뿐 아니라 실행 파일 hash와 Authenticode 상태를
   출력하고, unsigned 배포물이 App Control 환경에서 재평가 차단될 수 있음을 명확히 경고한다.
6. Qdrant 프로세스 생성 실패 진단에 실행 파일 절대 경로, SHA-256, 서명 상태, native error code와
   오류 이름을 포함한다. 비밀값과 전체 환경 변수는 출력하지 않는다.
7. 운영 runbook에 다음 절차를 기록한다.
   - Qdrant storage 백업과 복구 검증
   - 적용 정책 및 Code Integrity 이벤트 수집
   - 서명된 runtime 교체 또는 정책 배포
   - 서버 재시작과 `/health`, Qdrant `/healthz`, 기존 collection 검증
8. 설치물 검증과 오류 진단을 회귀 테스트로 고정한다.

## 완료 조건

- 장애 당시 또는 동일 재현에서 차단한 정책 이름과 GUID가 확인되고, SAC/별도 App Control/잔류
  정책 중 하나로 원인이 증거 기반으로 확정된다.
- Qdrant 프로세스가 재부팅과 반복 재시작 후에도 정상 생성되고 기존 vector storage와 collection을
  그대로 읽는다.
- 운영에 사용하는 Qdrant runtime은 공인 서명 또는 명시적으로 관리되는 App Control 정책에 의해
  허용되며, 일시적인 평판 캐시만을 가용성 조건으로 사용하지 않는다.
- SAC에는 파일 단위 예외가 없다는 제약과 Windows edition별 가능한 정책 운영 방식이 runbook에
  기록된다.
- 오류 4551 재현 테스트 또는 process starter 모의 테스트에서 hash, 서명 상태, native error가
  진단 메시지에 포함된다.
- `setupRuntime`, 서버 시작, Qdrant health 및 기존 collection 재사용 검증이 통과한다.
- 전체 테스트가 통과한다.

## 제외 범위

- 원인 증거 없이 Smart App Control 또는 Windows 보안 기능 전체를 영구 비활성화하는 변경
- 자체 서명 인증서를 로컬 trusted root에 추가하는 방식으로 SAC를 우회하는 시도
- Qdrant storage 삭제 또는 빈 collection으로의 재초기화
- 이번 장애와 무관한 Qdrant 검색, embedding 또는 memory domain 동작 변경
