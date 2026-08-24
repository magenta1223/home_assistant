# 원격 서버 데이터베이스 전체 초기화 계획

- 상태: 실행 전 계획
- 대상 서버: Windows에서 `C:\homeServers`를 작업 디렉터리로 사용하는 운영 서버
- 런타임 작업: 기본값 `HomeSecondBrain`
- 원칙: 이 문서는 계획만 정의한다. 별도 실행 승인 전에는 서버 중지나 데이터 이동·삭제를 수행하지 않는다.

## 배경

초기 시험 데이터가 잘못된 열람자 범위로 저장되어, 같은 source record를 올바른 범위로 다시
등록할 수 없다. 개별 source, evidence, canonical memory의 권한 수정 기능을 먼저 구현하지 않고,
운영 데이터가 아직 적다는 전제에서 모든 영속 데이터를 비운 뒤 올바른 열람자로 다시 주입한다.

이 초기화는 지식 데이터만 지우는 작업이 아니다. SQLite 하나에 등록 사용자, Slack identity,
대기 질문, 대화 세션, 수신 이벤트 기록, source record, canonical memory, evidence, 열람자 ACL,
인덱싱 outbox가 함께 저장되어 있으므로 모두 초기화된다.

## 초기화 대상과 보존 대상

기본 설정을 사용할 때의 대상은 다음과 같다. 삭제 직전 서버 로그와 실제 설정으로 절대 경로를
다시 확인하며, 경로가 다르면 아래 기본 경로를 그대로 사용하지 않고 작업을 중단한다.

| 구분 | 기본 절대 경로 | 처리 |
|---|---|---|
| SQLite | `C:\homeServers\db\homeAssistant.sqlite` | 라이브 경로에서 제거 |
| SQLite WAL | `C:\homeServers\db\homeAssistant.sqlite-wal` | 존재하면 함께 제거 |
| SQLite shared memory | `C:\homeServers\db\homeAssistant.sqlite-shm` | 존재하면 함께 제거 |
| Qdrant vector storage | `C:\homeServers\runtime\qdrant\storage` | 라이브 경로에서 제거 |
| Qdrant snapshots | `C:\homeServers\runtime\qdrant\snapshots` | 존재하면 함께 제거 |

다음 항목은 보존한다.

- `runtime\qdrant\qdrant.exe`와 설치 manifest 등 Qdrant 실행 파일
- `runtime\ollama\` 전체와 embedding model
- `.env`, 작업 스케줄러 설정, Codex 로그인, 애플리케이션 배포본과 소스 코드
- `logs\`와 `runtime\deploy\`의 운영·배포 로그
- 원본 텍스트 및 카카오톡 내보내기 파일
- Codex CLI 자체가 보관하는 과거 thread. SQLite의 session 연결이 사라지므로 애플리케이션은 이를
  다시 사용하지 않지만, 이번 DB 초기화 범위에서는 별도로 삭제하지 않는다.

## 사용자 영향

- 모든 Slack 사용자는 미등록 상태가 되며 첫 DM에서 이름 등록을 다시 해야 한다.
- 기존 Slack 질문 대기 상태와 10분 conversation session은 복구되지 않는다.
- 기존 source, memory, evidence, ACL 및 semantic vector는 모두 사라진다.
- 새 제한 공개 데이터를 넣기 전에 **열람 대상자 전원이 먼저 Slack 등록을 완료해야 한다.**
  등록되지 않은 사람은 `/knowledge`의 열람 사용자 목록에 나타나지 않는다.
- HTTP API token 환경 설정은 보존되지만, Slack 등록 사용자 ID는 재등록 과정에서 새로 생성될 수
  있다. 재주입 시 과거 ID를 재사용한다고 가정하지 않고 현재 화면의 표시 이름을 기준으로 선택한다.

## 실행 전 조건

1. 원본 데이터가 모두 다시 주입 가능한 상태인지 확인한다.
2. 각 source별 목표 범위를 `전체 공개` 또는 정확한 열람자 표시 이름 목록으로 정리한다.
3. 열람 대상자 전원이 초기화 후 먼저 Slack 등록을 할 수 있는 시간대를 정한다.
4. 매일 04:00 자동 배포와 겹치지 않는 maintenance window를 잡는다.
5. 현재 예약 작업 이름, 저장소 루트, DB 경로와 Qdrant 경로를 읽기 전용으로 확인한다.
   - 작업 스케줄러의 실행 명령과 시작 위치
   - `logs\server.log`의 최근 `Database:` 기록
   - 포트 `8080`, `6333`, `11435`의 소유 프로세스와 실행 경로
   - `QDRANT_URL`, `QDRANT_COLLECTION`에 운영 override가 있는지 여부
6. 위 경로 중 하나라도 `C:\homeServers` 밖을 가리키거나 예상과 다르면 삭제 계획을 수정할 때까지
   중단한다.

## 실행 계획

### 1. 현재 상태 기록과 격리 백업 준비

1. `C:\homeServers-reset-backups\<yyyyMMdd-HHmmss>`처럼 라이브 DB 경로 밖에 이번 작업 전용
   격리 디렉터리를 만든다.
2. 현재 Git SHA, 예약 작업 상태, `/health` 결과, SQLite 파일 크기·수정 시각·SHA-256, Qdrant
   storage와 snapshots의 파일 목록·전체 크기를 기록한다.
3. 가능하면 정지 후 읽기 전용 SQLite 조회로 아래 테이블의 row count를 기록한다.
   - `registered_users`, `conversation_identities`
   - `source_records`, `source_record_viewers`
   - `memories`, `memory_evidence`, `memory_viewers`
   - `indexing_outbox`
   - `pending_registration_questions`
   - `slack_codex_sessions`, `slack_codex_active_sessions`, `slack_message_receipts`
4. 백업 디렉터리와 대상 경로를 각각 절대 경로로 해석한 뒤, 서로 겹치지 않고 대상이 예상한
   파일·디렉터리와 정확히 일치하는지 확인한다. glob이나 재귀적인 상위 경로 삭제는 사용하지 않는다.

### 2. 애플리케이션을 완전히 정지

1. 자동 배포 작업이 maintenance window 중 실행될 가능성이 있으면 기존 enabled 상태를 기록하고
   일시적으로 비활성화한다.
2. `HomeSecondBrain` 예약 작업을 정상 중지하고 최대 30초 기다린다.
3. 포트 `8080`, `6333`, `11435`가 모두 닫혔는지 확인한다. 남은 프로세스가 있으면 실행 파일 또는
   명령줄이 `C:\homeServers`에 속하는지 검증한 경우에만 해당 process tree를 종료한다.
4. 소유권을 증명할 수 없는 프로세스가 포트를 사용하거나 파일 handle이 남아 있으면 DB 작업을
   진행하지 않고 중단한다.

### 3. 라이브 DB를 가역적으로 제거

1. SQLite 본체와 존재하는 `-wal`, `-shm` 파일만 `-LiteralPath`에 해당하는 명시적 경로로 격리
   디렉터리의 `sqlite\` 아래로 이동한다.
2. Qdrant의 `storage`와 `snapshots` 디렉터리만 격리 디렉터리의 `qdrant\` 아래로 이동한다.
3. `db` 디렉터리, `runtime\qdrant` 루트, `qdrant.exe`, `runtime\ollama`는 이동하거나 삭제하지 않는다.
4. 라이브 대상 다섯 곳이 사라졌고 격리본의 파일 수·크기와 사전 기록이 일치하는지 확인한다.

이 단계에서는 즉시 영구 삭제하지 않는다. 라이브 서비스 관점에서는 완전히 빈 상태로 시작하지만,
재기동 또는 재주입 검증 실패 시 원상 복구할 수 있다.

### 4. 빈 상태로 재기동

1. `HomeSecondBrain` 예약 작업을 시작한다.
2. 최대 180초 동안 `http://127.0.0.1:8080/health`가 `{"status":"ok"}`를 반환하는지 확인한다.
3. `logs\server.log`에서 다음을 확인한다.
   - 예상 경로에 새 SQLite가 생성됨
   - schema 생성 또는 migration 오류가 없음
   - Qdrant와 Ollama가 정상 기동함
   - Slack Socket Mode가 정상 연결됨
4. 자동 배포 작업을 일시 비활성화했다면 기존 enabled 상태로 복구한다.

### 5. 초기화 검증

1. 새 SQLite의 생성 시각과 hash가 격리본과 다르고, schema 외 사용자 데이터 row가 0인지 확인한다.
2. Qdrant `/collections`를 조회한다. `canonical_memories` collection이 아직 없거나, 요청 과정에서
   생성됐다면 point count가 0이어야 한다.
3. `/knowledge` 사용자 목록이 비어 있고, Slack DM이 기존 사용자를 자동 인식하지 않고 등록 흐름을
   시작하는지 확인한다.
4. 이전 질문을 보내도 과거 Codex session을 재개하지 않는지 로그로 확인한다.

이 중 하나라도 실패하거나 이전 memory/vector가 검색되면 재주입하지 않고 즉시 rollback한다.

### 6. 사용자 재등록 후 데이터 재주입

1. 제한 공개 데이터의 모든 열람 대상자가 Slack DM에서 이름 등록을 먼저 완료한다.
2. `/knowledge` 열람 사용자 목록에 목표 표시 이름이 모두 한 번씩 나타나는지 확인한다.
3. 소량의 시험 source 하나를 목표 ACL로 주입한다.
   - 허용 사용자의 DM 질문에는 memory가 검색되어야 한다.
   - 비허용 사용자의 DM 질문에는 해당 memory가 노출되지 않아야 한다.
   - source evidence와 memory의 열람자 범위가 일치해야 한다.
4. 시험 검증을 통과한 뒤 source별 체크리스트에 따라 나머지 데이터를 재주입한다.
5. 각 source는 첫 주입부터 최종 열람 범위를 정확히 선택하고, 성공 결과와 선택한 표시 이름을
   작업 기록에 남긴다.

### 7. 종료와 영구 삭제

1. 재주입 데이터 건수, ACL 표본, Slack 답변과 semantic 검색을 검증한다.
2. 최소 한 번의 정상 재시작 후에도 새 데이터만 유지되는지 확인한다.
3. 사용자가 새 상태를 최종 승인한 후에만 격리 백업을 영구 삭제한다. 권장 보존 기간은 7일이다.
4. 영구 삭제 전에도 격리 디렉터리의 절대 경로가
   `C:\homeServers-reset-backups\<이번 작업 timestamp>`와 정확히 일치하는지 다시 확인한다.

## Rollback 계획

재기동, 초기화 검증 또는 시험 ACL 검증이 실패하면 다음 순서로 복구한다.

1. `HomeSecondBrain` 예약 작업을 다시 완전히 정지하고 세 포트가 닫힌 것을 확인한다.
2. 실패한 새 SQLite와 새 Qdrant storage/snapshots를 별도의 `failed-new-state` 디렉터리로 이동한다.
3. 격리해 둔 SQLite 본체·WAL·SHM과 Qdrant storage/snapshots를 원래의 정확한 경로로 되돌린다.
4. 예약 작업을 시작하고 `/health`, 기존 사용자 인식, 기존 memory 검색을 확인한다.
5. 원인이 해결될 때까지 초기화 또는 재주입을 반복하지 않는다.

## 완료 조건

- 새 SQLite에 과거 사용자, source, memory, evidence, ACL, outbox, Slack session이 남아 있지 않다.
- Qdrant에 과거 canonical memory vector가 남아 있지 않다.
- 열람 대상자 전원이 데이터 주입 전에 재등록되어 선택 목록에 나타난다.
- 시험 제한 memory가 허용 사용자에게만 노출되고 비허용 사용자에게는 노출되지 않는다.
- 모든 원본 데이터가 의도한 최종 열람 범위로 재주입된다.
- 정상 재시작과 `/health` 검증을 통과한다.
- 최종 승인 전까지 rollback 가능한 격리본이 보존되고, 승인 후 명시적으로 영구 삭제된다.

## 제외 범위

- 개별 source, evidence 또는 canonical memory의 ACL 수정 기능 구현
- Qdrant 또는 Ollama 실행 파일 재설치
- `.env`, Slack app 설정, HTTP API token, Codex 로그인 변경
- Codex CLI 자체 thread 저장소와 운영 로그 삭제
- 원격 서버 접속 정보나 권한 체계 변경
