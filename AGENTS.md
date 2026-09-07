# AGENTS.md

## Project

This project is a low-maintenance **home second brain** for a family, not a general chat assistant or household project-management system.

- **Memory** stores facts, rules, assets, events, and concise decision history with source evidence and access control.
- **Task** only assigns work to a family member and records completion.
- **Notification** proactively delivers time-, condition-, or task-triggered information.
- **Review** periodically surfaces notable changes, unfinished work, recurring problems, and upcoming concerns.

Prefer memory-backed retrieval over a new structured feature. Add a dedicated service only when the behavior needs durable state changes, proactive delivery, or coordination between family members.

Knowledge enters explicitly from text or Kakao records, receives PUBLIC or immutable user-based access, and is analyzed into canonical topics and memories. Registered users retrieve and discuss those memories through authenticated HTTP. Slack is a frozen legacy adapter: preserve it, fix security or data-loss defects, and eventually remove it; do not add Slack features or restore a general chat surface.

The repository is also a hands-on environment for learning implementation, deployment, and operations. Learning value and understandable end-to-end behavior matter as much as delivery speed.

## Working Principles

- Follow explicit, in-scope user instructions. Do not substitute a plan or adjacent outcome. Ask only when ambiguity would materially change the result or a real constraint blocks the requested action.
- Keep changes simple and necessary: apply KISS, YAGNI, DRY, clear domain modeling, and clean architecture without speculative abstractions.
- Make surgical changes. Preserve existing style and unrelated work; remove only debris created by the current change.
- Respect dependency boundaries: business rules stay in `domain` and `application`; frameworks and external systems stay behind inbound and outbound adapters.
- Preserve application `UserId` values, memory ACLs, evidence, request deduplication, and the ten-minute conversation idle lease.
- Work incrementally toward verifiable outcomes. Add focused regression tests for behavior changes and run the narrowest relevant checks before broader ones.
- Explain important design choices, runtime boundaries, failure behavior, verification, and recovery clearly enough for the user to learn from the work.
- Treat work explicitly reserved as a user-owned learning task as off-limits until the user asks for help or implementation.
- Update the nearest use-case `README.md` when ports, orchestration order, or failure behavior changes.
- Treat production operations carefully: `C:\homeServers` is not production. Use `ssh homeserver`, verify the remote hostname is `HOMESERVER`, and do not start production services or managed runtimes locally unless explicitly asked.

Detailed feature status and operational procedures belong in `docs/` and package-level `README.md` files, not here.
