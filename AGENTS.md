# AGENTS.md

## Project

This project is a low-maintenance **home second brain** for a family, not a general chat assistant or household project-management system.

- **Memory** stores facts, rules, assets, events, and concise decision history with source evidence and access control.
- **Task** only assigns work to a family member and records completion.
- **Notification** proactively delivers time-, condition-, or task-triggered information.
- **Review** periodically surfaces notable changes, unfinished work, recurring problems, and upcoming concerns.

Prefer memory-backed retrieval over a new structured feature. Add a dedicated service only when the behavior needs durable state changes, proactive delivery, or coordination between family members.

Knowledge enters explicitly from text or Kakao records, receives PUBLIC or immutable user-based access, and is analyzed into canonical topics and memories. Registered users retrieve and discuss those memories through authenticated HTTP. HTTP is the only supported user-facing channel. Slack integration has been removed; do not restore it or a general chat surface.

The repository is also a hands-on environment for learning implementation, deployment, and operations. Learning value and understandable end-to-end behavior matter as much as delivery speed.

## Working Principles

- Follow explicit, in-scope user instructions. Do not substitute a plan or adjacent outcome. Ask only when ambiguity would materially change the result or a real constraint blocks the requested action.
- Keep changes simple and necessary: apply KISS, YAGNI, DRY, clear domain modeling, and clean architecture without speculative abstractions.
- For memory organization design, follow `docs/topic-adoption-criteria.md` and the current Memory + Topic design in `docs/topic-structure-proposal.md`; earlier proposals do not override them.
- Make surgical changes. Preserve existing style and unrelated work; remove only debris created by the current change.
- Respect dependency boundaries: business rules stay in `domain` and `application`; frameworks and external systems stay behind inbound and outbound adapters.
- Preserve application `UserId` values, memory ACLs, evidence, request deduplication, and the ten-minute conversation idle lease.
- Work incrementally toward verifiable outcomes. Add focused regression tests for behavior changes and run the narrowest relevant checks before broader ones.
- Explain important design choices, runtime boundaries, failure behavior, verification, and recovery clearly enough for the user to learn from the work.
- Treat work explicitly reserved as a user-owned learning task as off-limits until the user asks for help or implementation.
- Update the nearest use-case `README.md` when ports, orchestration order, or failure behavior changes.
- Treat production operations carefully: `C:\homeServers` is not production. Use `ssh homeserver`, verify the remote hostname is `HOMESERVER`, and do not start production services or managed runtimes locally unless explicitly asked.

## 코드 가독성

- 하나의 메서드에서는 하나의 추상화 수준만 다룬다. 상위 메서드는 세부 구현이 아니라 의도와 전체 흐름을 보여줘야 한다.
- 코드를 별도 메서드로 분리할 때는 실제로 이해하기 쉬워지는 경우에만 한다. 서로 다른 수준의 로직을 뒤섞거나 불필요하게 잘게 나누지 않는다.
- 흐름을 제어하기 위해 단순 Boolean, null, 변경 가능한 임시 변수를 사용하기보다 의미가 명확한 상태와 결과 타입을 사용한다.
- 무엇을 할지 결정하는 로직과 실제 작업을 수행하는 로직을 분리한다.
- 관련 없는 세부 구현을 따라가지 않아도 정상 흐름을 이해할 수 있게 한다. 예외적인 흐름은 명확한 이름을 붙여 격리한다.
- 리팩터링할 때 명시적으로 요청받지 않은 외부 동작은 변경하지 않는다.

Detailed feature status and operational procedures belong in `docs/` and package-level `README.md` files, not here.
