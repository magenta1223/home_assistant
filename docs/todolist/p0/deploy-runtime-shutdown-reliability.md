# 자동 배포 런타임 종료 신뢰성

- 상태: 구현 완료, 원격 배포 검증 대기
- 우선순위: P0
- 선행 작업: 없음

## 문제

`scripts/deploy-master.ps1`이 배포를 위해 `HomeSecondBrain` 예약 작업과 프로젝트 런타임을
종료하는 과정에서 이미 종료된 자식 PID를 다시 종료하려다 실패했다. 전체 테스트와 원격
`master` fast-forward는 성공했지만, 새 배포본을 만들기 전에 스크립트가 중단되어 서버가 정지
상태로 남았다.

2026-08-25 실제 실패 흐름은 다음과 같다.

1. 포트 `8080`, `6333`, `11435`의 listener PID로 Java 애플리케이션, Qdrant, Ollama를 모두
   수집했다.
2. Java PID에 `taskkill /T /F`를 실행하면서 자식 Qdrant와 Ollama도 함께 종료됐다.
3. 이미 수집해 둔 Qdrant PID를 다음 반복에서 다시 `taskkill`하려는 사이 프로세스가 사라졌다.
4. `taskkill`이 "프로세스를 찾을 수 없음"을 반환했고, 스크립트는 이를 정상적인 종료 경쟁으로
   처리하지 못한 채 전체 배포를 실패시켰다.
5. 실패가 `Stop-RuntimeTask` 이후, 배포본 생성의 제한적인 복구 블록 이전에 발생해
   `HomeSecondBrain`을 다시 시작하는 보상 동작도 실행되지 않았다.

현재 구현에는 종료 직전 `Get-Process` 확인과 `taskkill` 실패 후 재확인이 있지만, 확인과 종료
사이에는 본질적으로 경쟁 조건이 있다. 또한 native command의 stderr/exit code가 PowerShell의
`$ErrorActionPreference = "Stop"`과 상호작용하면 수동 exit-code 판정에 도달하기 전에 실패할 수
있다. 따라서 사전 존재 확인만으로는 문제를 제거할 수 없다.

## 목표

- parent process tree 종료로 자식 PID가 함께 사라지는 상황을 정상적이고 idempotent한 종료로
  처리한다.
- 실제로 살아 있는 repository-owned 프로세스를 종료하지 못한 경우만 배포 실패로 판정한다.
- 런타임 정지 이후 어느 단계에서 실패하더라도 가능한 경우 기존 배포본으로 서비스를 다시
  시작한다.
- 자동 배포 실패가 서버의 장기 중단으로 이어지지 않도록 회귀 검증을 추가한다.

## 구현 계획

1. 현재 listener PID 목록과 Win32 parent/child 관계를 함께 수집해 parent를 먼저 처리한다. parent
   tree가 child를 제거하면 child는 건너뛰고, parent만 먼저 사라져 child가 남으면 child를 이어서
   종료한다.
2. 각 종료 직전에 프로세스 존재와 repository ownership을 다시 확인하되, 확인 이후 사라지는
   경쟁도 성공으로 처리한다.
3. `taskkill`의 stdout, stderr와 exit code를 `$ErrorActionPreference`의 조기 중단과 분리해
   명시적으로 판정한다.
   - exit code가 0이면 성공
   - non-zero여도 해당 PID와 프로젝트 포트가 이미 사라졌으면 성공
   - PID가 여전히 존재하거나 프로젝트 포트가 남아 있을 때만 실패
4. 전체 종료 후 포트 `8080`, `6333`, `11435`를 다시 조회해 최종 상태를 단일 기준으로 검증한다.
5. `Stop-RuntimeTask`를 호출한 시점부터 runtime restart 책임을 상위 `try/finally` 또는 명시적인
   상태 머신으로 관리한다.
   - 배포 성공 시 새 배포본 시작
   - 테스트 이후 종료·빌드·검증 중 실패 시 기존 또는 현재 설치본 시작 시도
   - restart까지 실패하면 원래 오류와 restart 오류를 모두 로그에 남김
6. 프로세스 조회·종료와 예약 작업 제어를 주입 가능한 작은 경계로 분리하고 다음 사례를 자동화해
   검증한다.
   - root 종료가 여러 listener 자식 PID를 동시에 제거함
   - 존재 확인 직후 PID가 사라짐
   - `taskkill` non-zero지만 PID와 포트는 이미 사라짐
   - `taskkill` non-zero이고 PID 또는 포트가 남음
   - 런타임 정지 후 `installDist` 실패 시 기존 서버 재시작
   - 재시작 자체도 실패하는 이중 실패 로그
7. `docs/server-auto-deploy.md`에 종료 경쟁 처리, 실패 시 restart 보장과 수동 복구 확인 절차를
   반영한다.

## 완료 조건

- Java parent, Qdrant, Ollama PID가 listener 목록에 함께 있어도 process tree가 중복 종료되지 않는다.
- 종료 도중 자식 PID가 먼저 사라져도 자동 배포가 실패하지 않는다.
- 프로젝트 소유가 확인되지 않은 PID는 계속 종료를 거부한다.
- 실제 project-owned PID 또는 세 포트가 남아 있을 때는 배포가 명확히 실패한다.
- 런타임 정지 이후의 배포 실패에서 `HomeSecondBrain` 재시작이 시도되고, 성공하면 `/health`가
  다시 `ok`가 된다.
- 테스트, `:app:installDist`, 예약 작업 재시작, `/health` 확인과 성공 SHA 기록이 정상 완료된다.
- 위 경쟁 조건과 restart 보상 경로가 자동화된 회귀 테스트로 고정된다.

## 임시 운영 절차

수정 전 같은 장애가 발생하면 다음 순서로 복구한다.

1. 원격 `master`의 HEAD와 전체 테스트 성공 여부를 확인한다.
2. 포트 `8080`, `6333`, `11435`가 모두 닫혔는지 확인한다.
3. `gradlew.bat --no-daemon :app:installDist`로 배포본을 생성한다.
4. `HomeSecondBrain` 예약 작업을 시작하고 `/health`가 `ok`인지 확인한다.
5. 성공한 HEAD를 `runtime/deploy/deployed-sha.txt`에 원자적으로 기록한다.

## 현재 구현 결과

2026-08-25에 다음 변경을 구현했다.

- `deploy-runtime-control.ps1`에 parent 우선 process tree 종료, native `taskkill` 결과 캡처와 배포
  실패 후 runtime 복구 경계를 분리했다.
- repository-owned process만 종료하는 기존 검증을 유지했다.
- PID가 확인 직후 사라지거나 parent 종료로 child가 먼저 사라진 경우를 성공으로 처리하고, 마지막
  세 포트 검증은 `deploy-master.ps1`이 계속 소유한다.
- 런타임 종료 시도 직전부터 health 성공 전까지 복구 책임을 유지하고, 실패 시 현재 설치본의
  예약 작업 시작과 health 확인을 수행한다.
- 원래 배포 실패와 recovery 실패를 별도 로그로 남긴다.
- PowerShell 회귀 테스트를 root Gradle `test`에 연결했다.

검증 결과:

- PowerShell 종료·복구 회귀 테스트 10개 통과
- 실제 존재하지 않는 PID에 대한 `taskkill` stderr/non-zero exit code 캡처 통과
- `gradlew test`에서 전체 Kotlin 테스트와 PowerShell 테스트 통과
- `deploy-master.ps1` PowerShell parser 검증 통과

남은 완료 조건은 변경을 커밋·배포한 뒤 원격 `HomeSecondBrain` 예약 작업으로 실제 parent/child
종료, 재기동과 `/health` 성공을 한 차례 확인하는 것이다. 이 검증 전까지 P0 목록에서 유지한다.

2026-08-29 backlog 최신화 중 운영 상태를 읽기 전용으로 확인했다.

- 접속 대상 hostname은 `HOMESERVER`였다.
- 원격 repository HEAD와 `runtime/deploy/deployed-sha.txt`는 모두
  `198b35328a37ba408e07d35a1b027de37d01c7f1`이었다.
- `/health`는 `{"status":"ok"}`를 반환했다.
- 종료·복구 변경은 아직 로컬 미커밋 상태이므로 이 확인은 기존 배포본의 정상 상태만 증명한다.
  새 종료·복구 경로의 운영 검증으로 간주하지 않으며 P0를 유지한다.

## 제외 범위

- repository ownership 확인을 제거하고 임의의 listener를 강제 종료하는 방식
- 포트 종료 검증을 생략하거나 고정 sleep만 늘리는 방식
- 실패를 숨기고 health 확인 없이 성공 SHA를 기록하는 방식
- Windows 작업 스케줄러를 다른 서비스 관리자로 교체하는 작업
