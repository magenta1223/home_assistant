# Slack Kakao Subplan 6: Ubuntu Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 완성된 Slack Socket Mode 앱을 Ubuntu Server에서 부팅 시 자동 시작되고 장애 시 복구되는 서비스로 운영한다.

**Architecture:** Gradle `installDist` 결과를 `/opt/home-second-brain`에 배포하고 전용 사용자로 실행한다. secret은 `/etc/home-second-brain.env`, SQLite는 `/var/lib/home-second-brain`에 둔다.

**Tech Stack:** Ubuntu Server, systemd, JVM, Gradle application distribution

---

### Task 1: 배포 설정 파일

**Files:**
- Create: `deploy/systemd/home-second-brain.service`
- Create: `deploy/systemd/home-second-brain.env.example`
- Create: `docs/ubuntu-slack-bot-deployment.md`

- [ ] unit에 `User=home-second-brain`, `WorkingDirectory=/opt/home-second-brain`, `EnvironmentFile=/etc/home-second-brain.env`를 지정한다.
- [ ] `ExecStart=/opt/home-second-brain/bin/app`, `Restart=on-failure`, `RestartSec=5`를 지정한다.
- [ ] example env에 Slack 토큰 이름, AI provider 설정, DB 경로만 포함하고 실제 secret은 넣지 않는다.
- [ ] 배포 문서에 JDK 설치, 사용자 생성, 디렉터리 권한, artifact 복사, 서비스 활성화 명령을 기록한다.
- [ ] `systemd-analyze verify deploy/systemd/home-second-brain.service`로 unit 문법을 검증한다.
- [ ] `git commit -m "docs: add Ubuntu systemd deployment"`로 커밋한다.

### Task 2: 배포 artifact 검증

**Files:**
- Modify if required: `app/build.gradle.kts`
- Test: generated `app/build/install/app/`

- [ ] `./gradlew clean :app:installDist`를 실행한다.
- [ ] `app/build/install/app/bin/app` 실행 스크립트와 runtime dependency가 생성되는지 확인한다.
- [ ] 별도 임시 DB 경로와 테스트용 토큰 누락 상태에서 앱이 의도한 설정 오류로 종료되는지 확인한다.
- [ ] 실제 secret을 주입한 Ubuntu 환경에서 Ktor health endpoint와 Socket Mode 연결 로그를 확인한다.
- [ ] `git commit -m "build: prepare application distribution"`로 커밋한다. 변경 파일이 없으면 커밋하지 않는다.

### Task 3: Ubuntu 설치

- [ ] 지원 JDK를 설치하고 `java -version`을 기록한다.
- [ ] `home-second-brain` system user를 만든다.
- [ ] `/opt/home-second-brain`, `/var/lib/home-second-brain`을 만들고 서비스 사용자 소유로 설정한다.
- [ ] `/etc/home-second-brain.env`를 생성하고 mode `600`, owner `root:root`로 설정한다.
- [ ] 배포본과 unit을 설치한다.
- [ ] `sudo systemctl daemon-reload`를 실행한다.
- [ ] `sudo systemctl enable --now home-second-brain`을 실행한다.
- [ ] `systemctl status home-second-brain`과 `journalctl -u home-second-brain`에서 연결 성공을 확인한다.

### Task 4: E2E acceptance test

- [ ] 일반 TXT를 DM에 올려 Kakao 형식 오류가 오고 LLM이 호출되지 않는지 확인한다.
- [ ] 정상 Kakao TXT를 올려 처리 시작과 topic preview가 오는지 확인한다.
- [ ] 일부 topic만 선택하고 저장해 DB에 선택 topic만 존재하는지 확인한다.
- [ ] 전체 원본 Kakao 메시지가 DB에 import되었는지 확인한다.
- [ ] 승인 후 preview row와 원문이 삭제되었는지 확인한다.
- [ ] 다른 사용자가 검토 버튼을 실행할 수 없는지 확인한다.
- [ ] 동일 interaction을 반복해 중복 topic이 생기지 않는지 확인한다.
- [ ] 서비스를 재시작하고 Socket Mode가 자동 재연결되는지 확인한다.
- [ ] Ubuntu를 재부팅하고 서비스가 자동 시작되는지 확인한다.

## 완료 조건

- 외부 HTTPS endpoint와 port forwarding 없이 Slack 이벤트를 받는다.
- 앱과 SQLite 데이터가 재부팅 후 유지된다.
- 프로세스 실패 후 systemd가 자동 재시작한다.
- 정상·오류·취소·부분 승인 E2E 시나리오가 모두 통과한다.

