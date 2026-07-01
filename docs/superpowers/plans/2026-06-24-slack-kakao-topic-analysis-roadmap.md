# Slack Kakao Topic Analysis Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement each subplan. Complete and verify one subplan before starting the next.

**Goal:** 봇 DM에 업로드한 KakaoTalk TXT를 자동 분석하고, 사용자가 선택한 topic만 확인 후 저장하는 기능을 Ubuntu Server에 배포한다.

**Architecture:** Ktor 애플리케이션과 Slack Socket Mode listener를 하나의 JVM 프로세스로 실행한다. Slack은 입력과 승인 UI만 담당하고, 기존 Kakao parser, topic analyzer, preview, repository 흐름을 재사용한다.

**Tech Stack:** Kotlin 2.2, Ktor 3.1, Slack Bolt for Java 1.49.0, Socket Mode, Exposed, SQLite, systemd

---

## 확정된 동작

- 업로드 위치는 봇과 사용자의 1:1 DM으로 제한한다.
- 입력은 UTF-8 `.txt`, 최대 10 MiB로 제한한다.
- Kakao export가 아니면 LLM을 호출하지 않는다.
- 분석 결과는 preview로만 저장하고 영구 topic은 만들지 않는다.
- 사용자는 Slack modal에서 topic을 복수 선택한다. 모든 topic을 기본 선택한다.
- 승인 시 파일 전체 Kakao 메시지와 선택 topic만 저장한다.
- 승인, 취소, 빈 선택 후 preview 원문을 즉시 삭제한다.
- Slack 원본 파일은 삭제하지 않는다.
- Ubuntu Server에서는 `systemd`가 앱을 자동 시작하고 장애 시 재시작한다.

## 실행 순서

- [ ] [Subplan 1: Topic analysis 경계와 preview 수명주기](2026-06-24-slack-kakao-01-analysis-boundary.md)
- [ ] [Subplan 2: Slack Socket Mode와 DM 파일 수신](2026-06-24-slack-kakao-02-socket-file-ingress.md)
- [ ] [Subplan 3: Kakao 검증과 비동기 분석](2026-06-24-slack-kakao-03-validation-analysis.md)
- [ ] [Subplan 4: Topic 선택 확인 UI](2026-06-24-slack-kakao-04-confirmation-ui.md)
- [ ] [Subplan 5: 선택 저장, 멱등성, 데이터 정리](2026-06-24-slack-kakao-05-save-cleanup.md)
- [ ] [Subplan 6: Ubuntu systemd 배포와 E2E 검증](2026-06-24-slack-kakao-06-ubuntu-deployment.md)

각 subplan 완료 시 해당 문서의 테스트와 `./gradlew build`가 통과해야 다음 단계로 이동한다.

