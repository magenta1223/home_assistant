# Windows master 자동 배포

`scripts/deploy-master.ps1`은 `origin/master`를 확인하고 새 커밋을 테스트한 뒤 Windows 서비스를
재시작한다. 배포 로그와 마지막 성공 SHA는 gitignored `runtime/deploy/` 아래에 저장한다.

## 전제 조건

- `HomeSecondBrain`이라는 Windows 서비스가 저장소 루트를 작업 디렉터리로 사용해 서버를 실행한다.
- 서비스와 예약 작업은 Git 및 Codex 로그인이 준비된 동일한 Windows 계정으로 실행한다.
- 서비스 계정은 저장소와 `runtime/`에 접근할 수 있어야 한다.
- 예약 작업은 서비스 재시작을 위해 가장 높은 권한으로 실행한다.

서비스 이름이 다르면 스크립트의 `-ServiceName` 인수로 지정한다.

## 수동 검증

관리자 PowerShell에서 다음 명령을 실행한다.

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File C:\homeServers\scripts\deploy-master.ps1
```

첫 실행도 현재 SHA를 테스트하고 서비스를 재시작한 뒤 배포 완료로 기록한다. 이후에는
`origin/master`가 바뀐 경우에만 배포한다.

## 작업 스케줄러 설정

5분 간격으로 아래 프로그램을 실행하는 작업을 만든다.

- 프로그램: `powershell.exe`
- 인수: `-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\homeServers\scripts\deploy-master.ps1`
- 시작 위치: `C:\homeServers`
- 사용자 로그온 여부와 관계없이 실행
- 가장 높은 수준의 권한으로 실행
- 이미 실행 중이면 새 인스턴스를 시작하지 않음

## 안전 조건

- 현재 branch가 `master`가 아니거나 worktree가 dirty하면 중단한다.
- local `master`가 `origin/master`로 fast-forward될 수 없으면 중단한다.
- 전체 테스트가 실패하면 서비스를 재시작하지 않는다.
- `/health`가 성공한 경우에만 성공 SHA를 기록한다. 실패한 SHA는 다음 주기에 다시 시도한다.
- Git checkout을 되돌리는 자동 rollback은 수행하지 않는다. health 실패 시 로그를 확인하고 수동으로
  이전 정상 커밋을 복구한다.
