# Slack app manifest

`slack-app-manifest.json`이 Slack 앱 기능과 권한의 저장소 기준 설정이다. Slash command를 추가하거나
삭제할 때는 manifest의 `features.slash_commands`와 해당 command 구현을 함께 변경한다.

## 원격 앱 갱신

Slack App Configuration Access Token과 대상 App ID를 환경 변수로 제공한 뒤 다음 작업을 실행한다.

```powershell
$env:SLACK_CONFIG_TOKEN = "configuration-access-token"
$env:SLACK_APP_ID = "A0123456789"
.\gradlew.bat updateSlackManifest
```

작업은 `apps.manifest.validate` 성공 후에만 `apps.manifest.update`를 호출한다. 토큰은 저장소나 `.env`
예시에 기록하지 않는다. 응답의 `permissions_updated`가 참이면 새 권한을 승인하도록 Slack 앱을
워크스페이스에 다시 설치한다.

Manifest update는 기존 설정 전체를 교체하므로 Slack 웹에서만 변경한 설정을 남겨두지 않는다. 원격
설정을 별도로 바꿨다면 먼저 export하여 이 파일에 반영한다.

## Slash command 확장

각 command는 `SlackSlashCommand` 구현 하나로 command listener와 modal/action callback을 함께
등록한다. `SlackSlashCommandRegistry`는 command 이름과 interaction callback ID 중복을 시작 시점에
거부한다. 중앙 listener에 command별 조건 분기를 추가하지 않는다.

Slack manifest 하나에는 slash command를 최대 50개까지만 선언할 수 있다. 기능이 그 이상으로
늘어나면 하나의 command 아래에 subcommand를 두는 방식을 우선 사용하고, 독립 command가 반드시
필요하면 Slack 앱을 분리한다.
