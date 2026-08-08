# 프로젝트 관리형 임베딩 서버

- 상태: DONE
- 우선순위: P0
- 완료일: 2026-08-08
- 목표: Windows 서버에서 프로젝트가 Ollama 설치·모델 준비·서버 생명주기를 직접 관리

## 문제

현재 애플리케이션은 `OLLAMA_BASE_URL`에 이미 실행 중인 Ollama 서버가 있다고 가정한다. 서버가
실행되지 않은 상태에서도 애플리케이션과 import 흐름은 시작되며, embedding 요청 시점에야 연결
실패가 발생한다. 실제 운영에서 발생한 인덱싱 실패는 이 외부 실행 전제 때문에 생겼다.

별도의 durable outbox나 인덱싱 재시도 worker를 먼저 추가하기보다, 애플리케이션이 필요한 로컬
임베딩 서버의 생명주기를 직접 소유해 이 전제를 제거한다.

## 결정

- 배포 대상은 Windows 10 22H2 이상 x86-64 서버다.
- 일반 Windows GUI installer 대신 Ollama가 서비스 통합 용도로 제공하는 standalone
  `ollama-windows-amd64.zip`을 사용한다.
- Ollama version, 공식 release URL, asset SHA-256을 프로젝트 manifest에 고정한다. setup 과정에서
  최신 version을 임의 조회하지 않는다.
- `runtime/ollama/<version>/`에 실행 파일을, `runtime/ollama/models/`에 모델을 둔다. 두 경로는
  source control에서 제외하고 binary upgrade와 모델 보관을 분리한다.
- 설치와 모델 download는 명시적인 `.\gradlew.bat setupEmbedding` task가 담당한다. 일반 애플리케이션
  시작 중에는 binary 또는 모델을 download하지 않는다.
- 기본 운영 모드는 애플리케이션이 `ollama serve` 자식 프로세스를 시작하는 managed mode다.
- 프로세스 실행과 HTTP embedding 호출은 `adapter-outbound/embedding/ollama`에 둔다.
- 실행 순서와 종료 연결은 composition root인 `app`이 담당한다.
- 애플리케이션은 자신이 생성한 프로세스만 종료한다. 포트를 점유한 다른 프로세스를 종료하거나
  기존 Ollama 인스턴스에 암묵적으로 연결하지 않는다.
- 모델 파일은 서버 시작 때마다 내려받지 않는다. setup task가 한 번 준비하고 런타임에는 모델 존재
  여부와 768차원 embedding 결과만 검증한다.
- embedding 서버가 준비되지 않으면 HTTP route와 Slack runtime을 열지 않고 애플리케이션 시작을
  실패시킨다.

## 실제 변경 내용

1. Ollama version, `ollama-windows-amd64.zip` 공식 release URL, SHA-256을 한 manifest에서 관리한다.
   version upgrade는 manifest와 checksum을 함께 바꾸고 setup task를 다시 실행하는 명시적 작업이다.
2. root `setupEmbedding` Gradle task와 이 task가 호출할 Windows setup 구현을 추가한다. 지원하지 않는
   OS/architecture에서는 download 전에 실패한다.
3. setup task는 고정 URL에서 ZIP을 임시 경로로 download하고 SHA-256이 manifest와 정확히 일치하는지
   확인한다. 검증 전 파일은 실행하지 않으며, 불일치하면 임시 파일을 제거하고 설치를 실패시킨다.
4. 검증된 ZIP을 임시 directory에 풀어 필수 실행 파일과 library를 확인한 뒤
   `runtime/ollama/<version>/`으로 원자적으로 승격한다. 중단된 download나 압축 해제가 정상 설치로
   보이지 않게 하고, 같은 version이 이미 검증돼 있으면 다시 받지 않는다.
5. setup task가 프로젝트 전용 loopback 주소에서 설치한 `ollama.exe serve`를 임시로 시작하고
   `OLLAMA_MODELS`를 `runtime/ollama/models/`로 지정한 뒤 기본 모델
   `qllama/multilingual-e5-base`를 준비한다. 모델이 이미 있으면 pull을 생략한다.
6. setup 종료 전에 작은 probe 입력으로 embedding 호출과 768차원 결과를 확인한다. setup이 시작한
   임시 서버와 하위 프로세스만 종료하며, 포트를 점유한 다른 프로세스는 건드리지 않는다.
7. runtime과 model directory를 `.gitignore`에 추가하고 download 진행률, 필요 disk 공간, 실패 원인을
   운영자가 확인할 수 있게 한다. binary, model, 임시 ZIP은 commit하지 않는다.
8. 프로젝트 전용 loopback 주소와 포트를 설정하고 `OLLAMA_HOST`를 자식 프로세스에만 전달한다.
   기존 `OLLAMA_BASE_URL`을 managed server의 실제 주소와 중복되는 설정으로 남기지 않는다.
9. `OllamaServerRuntime`은 manifest가 가리키는 프로젝트 내부 `ollama.exe`만 실행한다. 파일이 없으면
   PATH나 GUI 설치본으로 우회하지 않고 `.\gradlew.bat setupEmbedding` 실행 방법을 안내하며 시작을
   실패시킨다.
10. `OllamaServerRuntime`이 `ollama serve`의 stdout/stderr를 지속적으로 소비해
   프로세스 pipe가 막히지 않도록 한다. 로그에는 secret이나 embedding 입력을 기록하지 않는다.
11. 제한된 시작 시간 동안 HTTP readiness를 확인한다. 프로세스 조기 종료, timeout, 포트 점유를
   서로 구분해 보고한다.
12. readiness 이후 기본 모델 `qllama/multilingual-e5-base`의 존재 여부를 확인하고 작은 probe 입력으로
   embedding 호출과 768차원 결과를 검증한다. 검증이 끝나기 전에는 use case와 inbound runtime을
   시작하지 않는다.
13. `ApplicationServices`가 embedding runtime을 가장 먼저 시작하고 가장 나중에 종료하도록 생명주기를
   연결한다. 정상 종료를 먼저 요청하고 제한 시간 후에도 남아 있으면 해당 자식과 그 하위 프로세스만
   강제 종료한다.
14. 서버가 실행 중 예기치 않게 종료되면 상태를 unhealthy로 바꾸고 새 embedding 요청을 즉시 실패시킨다.
   자동 무한 재시작은 하지 않으며, 종료 원인과 최근 서버 로그의 제한된 진단 정보만 남긴다.
15. `/health`가 단순한 정적 `ok` 대신 embedding runtime의 준비 상태를 반영하게 한다. 준비가 끝나지
   않았거나 프로세스가 종료됐으면 정상 상태를 반환하지 않는다.
16. `AGENTS.md` 환경 설정, Windows setup과 local run 안내를 managed mode 기준으로 갱신한다.

## 검증 결과

- 가짜 ZIP으로 checksum 검증, 중복 setup의 download 생략, 중단된 download 정리와 재실행을 검증했다.
- 가짜 HTTP API와 실제 자식 process로 readiness 이후 시작, embedding 768차원 probe, 정상 종료,
  시작 실패 cleanup, 비정상 종료 후 unhealthy 전환을 검증했다.
- 이미 점유된 managed port에서는 자식 process를 시작하지 않는지 검증했다.
- application service가 embedding runtime을 먼저 시작하고 Slack을 먼저 종료하는 순서를 검증했다.
- embedding runtime 상태에 따라 `/health`가 200 또는 503을 반환하는지 검증했다.
- `.\gradlew.bat setupEmbedding --dry-run`으로 root task가 `:app:setupEmbedding` 진입점에 연결되는지
  확인했다.
- `.\gradlew.bat test --rerun-tasks` 통과: 75개 성공, 실패·오류·스킵 0개.
- 공식 asset은 Ollama `v0.30.8` Windows amd64 ZIP과 GitHub release digest
  `c2d26d97e698027329c252629d7113bbc05d874b49960cbb03e93a39ae9fd95c`로 고정했다.
- 실제 1.45GB binary와 모델 download는 자동 테스트에서 실행하지 않았다. 배포 Windows 서버에서
  `.\gradlew.bat setupEmbedding`을 명시적으로 한 번 실행한다.

## 완료 조건 확인

- 운영자가 애플리케이션보다 먼저 `ollama serve`를 별도로 실행할 필요가 없다.
- Ollama가 없는 Windows 서버도 `.\gradlew.bat setupEmbedding` 한 번으로 필요한 binary와 모델을 준비한다.
- 설치는 고정 version과 SHA-256으로 재현 가능하며, 두 번째 setup 실행은 불필요한 download를 하지 않는다.
- 임베딩 서버 또는 모델이 준비되지 않은 상태에서 애플리케이션이 정상 기동된 것처럼 보이지 않는다.
- 앱이 시작한 프로세스의 PID와 종료 책임이 명확하고, 앱 종료 후 orphan process가 남지 않는다.
- 기본 모델로 생성한 embedding이 Qdrant collection과 동일한 768차원임을 시작 시 검증한다.
- lifecycle, readiness, 실패 정리 동작이 자동 테스트로 고정된다.

## 제외 범위

- 인덱싱 outbox와 background retry worker
- memory placement 실패의 영속 기록과 재실행
- Qdrant 프로세스의 직접 기동
- 애플리케이션 시작 중 모델 자동 download
- 원격 또는 hosted embedding provider

## 참고 자료

- [Ollama Windows standalone CLI](https://docs.ollama.com/windows#standalone-cli)
- [Ollama 공식 releases](https://github.com/ollama/ollama/releases)
