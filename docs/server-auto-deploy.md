# Windows master 자동 배포

`scripts/deploy-master.ps1`은 하루에 한 번 `origin/master`를 확인한다. 새 커밋이 있으면 테스트하고
배포본을 만든 뒤 `HomeSecondBrain` 예약 작업을 재시작한다. 새 커밋이 없어도 장기 실행 상태를
초기화하기 위해 같은 예약 작업을 하루에 한 번 재시작한다. 배포 로그와 마지막 성공 SHA는
gitignored `runtime/deploy/` 아래에 저장한다.

## 전제 조건

- `HomeSecondBrain`이라는 부팅 트리거 예약 작업이 저장소 루트를 작업 디렉터리로 사용해 서버를
  사용하고, 저장소의 `run-server.cmd`를 실행한다.
- 런타임 작업과 배포 작업은 Git 및 Codex 로그인이 준비된 동일한 Windows 계정으로 실행한다.
- 작업 계정은 저장소와 `runtime/`에 접근할 수 있어야 한다.
- 두 예약 작업은 작업 재시작을 위해 가장 높은 권한으로 실행한다.

런타임 작업 이름이 다르면 스크립트의 `-RuntimeTaskName` 인수로 지정한다.

## 수동 검증

관리자 PowerShell에서 다음 명령을 실행한다.

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File C:\homeServers\scripts\deploy-master.ps1
```

첫 실행도 현재 SHA를 테스트하고 배포본을 만든 뒤 런타임 작업을 재시작하여 배포 완료로 기록한다.
이후에는 `origin/master`가 바뀐 경우에만 테스트와 배포본 생성을 반복하지만, 런타임 작업은 매일
재시작한다.

## 작업 스케줄러 설정

매일 오전 4시에 `HomeSecondBrainDailyDeploy` 작업으로 아래 프로그램을 실행한다.

- 프로그램: `powershell.exe`
- 인수: `-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\homeServers\scripts\deploy-master.ps1`
- 시작 위치: `C:\homeServers`
- 사용자 로그온 여부와 관계없이 실행
- 가장 높은 수준의 권한으로 실행
- 이미 실행 중이면 새 인스턴스를 시작하지 않음
- 예약된 시간이 누락되었으면 가능한 즉시 실행

## 안전 조건

- 현재 branch가 `master`가 아니거나 worktree가 dirty하면 중단한다.
- local `master`가 `origin/master`로 fast-forward될 수 없으면 중단한다.
- 전체 테스트가 실패하면 실행 중인 런타임 작업을 재시작하거나 배포본을 변경하지 않는다.
- 런타임 종료를 시작한 뒤 배포본 생성, 작업 시작 또는 health 확인이 실패하면 현재 설치된
  배포본으로 런타임 작업을 복구하고 `/health`를 다시 확인한다. 원래 실패와 복구 실패는 별도로
  기록한다.
- 작업 스케줄러가 남긴 프로세스는 실행 파일 또는 명령줄이 저장소 경로에 속하는지 확인한 뒤에만
  parent 우선으로 process tree를 종료한다. parent 종료로 child PID가 먼저 사라지거나 확인 직후
  PID가 종료되는 경쟁은 정상 종료로 처리한다.
- 종료 성공 여부는 개별 `taskkill` exit code만으로 판단하지 않고 포트 `8080`, `6333`, `11435`가
  모두 닫혔는지 최종 확인한다.
- `/health`가 성공한 경우에만 성공 SHA를 기록한다. 실패한 SHA는 다음 주기에 다시 시도한다.
- Git checkout을 되돌리는 자동 rollback은 수행하지 않는다. health 실패 시 로그를 확인하고 수동으로
  이전 정상 커밋을 복구한다.

## 배포 스크립트 회귀 테스트

`gradlew test`는 Kotlin 테스트와 함께 `scripts/test-deploy-runtime-control.ps1`을 실행한다. 이
테스트는 parent/child 중복 listener, 종료 경쟁, 소유권 거부, 실제 종료 실패, 배포 실패 후 runtime
복구와 recovery health 실패를 검증한다. PowerShell 테스트만 실행하려면 다음 명령을 사용한다.

```powershell
.\gradlew.bat testDeploymentScripts
```

자동 복구까지 실패하면 관리자 PowerShell에서 예약 작업과 세 포트를 확인하고 다음 순서로
복구한다.

1. `gradlew.bat --no-daemon :app:installDist`
2. `Start-ScheduledTask -TaskName HomeSecondBrain -TaskPath \`
3. `Invoke-RestMethod http://127.0.0.1:8080/health`

성공 SHA는 `/health`가 `ok`인 것을 확인한 뒤에만 `runtime/deploy/deployed-sha.txt`에 기록한다.
