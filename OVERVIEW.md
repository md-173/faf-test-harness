# faf-test-harness — Repo Overview

A headless test harness for [Forged Alliance Forever](https://faforever.com) (FAF). It mocks the desktop client and the game process so the lobby server, the ICE adapter, and FAF networking can be exercised end-to-end without a real game install or human in the loop.

This document describes everything currently in the repo, what it does today, and what it is intended to do.

---

## 1. What this project is for

FAF in production is a chain of components: a **desktop client** (lobby UI) → a **lobby server** (auth, matchmaking) → a **faf-ice-adapter** subprocess (NAT traversal / P2P) → the actual **Forged Alliance game** (which speaks the GPGNet binary protocol over a TCP loopback to the ICE adapter).

Testing this chain has historically required the real game binary and a human pressing buttons. The test harness replaces the two ends of the chain with deterministic, scriptable Java processes:

- **mock-client** stands in for the FAF desktop client.
- **mock-game** stands in for the Forged Alliance game executable.

The real `faf-ice-adapter` and a real (or staging) lobby server still run in the middle. That means the harness can exercise authentication, matchmaking, ICE/STUN/TURN negotiation, and GPGNet framing, on CI, without graphics or input.

Source for this intent: `documentation/project-spec.md`, `documentation/research/project-briefing.md`, and the architecture diagrams in `documentation/diagrams/`.

---

## 2. High-level architecture

```
                ┌─────────────────────┐
                │    Lobby Server     │   (real FAF prod/staging)
                └─────────▲───────────┘
                          │ WebSocket + OAuth
                          │
                ┌─────────┴───────────┐
                │     mock-client     │   spawns subprocesses,
                │ (orchestrator CLI)  │   proxies GPGNet
                └───┬─────────────┬───┘
            spawns │             │ spawns
                   ▼             ▼
        ┌──────────────────┐ ┌────────────────────┐
        │  faf-ice-adapter │ │     mock-game      │
        │   (real binary)  │◄┤  (GPGNet TCP peer) │
        └──────────────────┘ └────────────────────┘
                  ▲
                  │ ICE / STUN / TURN
                  ▼
              other peers
```

Four actors — two mocked by us (`mock-client`, `mock-game`), two real (`faf-ice-adapter`, lobby server). Detail and sequence flows live in `documentation/diagrams/architecture.md` and `documentation/diagrams/sequence-full-session.md`.

---

## 3. Repository layout

```
faf-test-harness/
├── build.gradle            ← root Gradle config (Java 21, Checkstyle, Spotless)
├── settings.gradle         ← includes: shared, mock-client, mock-game
├── gradlew, gradle/        ← Gradle wrapper
├── config/checkstyle/      ← Google Java style + suppressions
├── .github/                ← CI workflow + PR/issue templates
├── .env                    ← local dev defaults (lobby URL, ports)
├── scripts/                ← helper scripts
│
├── shared/                 ← cross-module utilities (logging today)
├── mock-client/            ← headless FAF client
├── mock-game/              ← headless FA game process
├── faf-test-harness/       ← (empty placeholder for future orchestrator)
│
├── documentation/          ← specs, research, diagrams, meeting notes
├── README.md               ← one-liner
├── CONTRIBUTING.md         ← team conventions (see §8)
└── LICENSE                 ← MIT
```

`settings.gradle` only wires up `shared`, `mock-client`, and `mock-game`. The `faf-test-harness/` directory is reserved for the top-level orchestrator and is not yet a built module.

---

## 4. Module: `shared/` — centralized logging (implemented)

Cross-cutting code lives here. Today it contains the **structured logging framework** all other modules use.

Package: `com.faforever.testharness.shared.logging`

| Class | Role |
|---|---|
| `LoggingSetup` | One-call initializer. Each module's `Main` calls it in a static block to set the component name (MDC key) and configure the log file path. |
| `JsonLineEncoder` | Logback encoder that emits JSONL — one JSON object per log event, with timestamp, component, level, logger, thread, message, and exception. |
| `ComponentConverter` | Logback pattern converter for `%component`. Reads MDC first, falls back to a `LoggerContext` property so async threads that don't inherit MDC still get tagged. |
| `ProcessOutputLogger` | Captures stdout/stderr of subprocesses, merges multi-line stack traces into single events, and re-emits them through SLF4J with the right component tag. Used when `mock-client` spawns `faf-ice-adapter` and `mock-game`. |

Two outputs by default:
- **Console** — human-readable: `[2026-04-17 12:00:00.000] [MockClient] [INFO ] Connected.`
- **File** — JSONL at `logs/<component>.jsonl` for machine parsing.

Configurable via env vars: `LOG_LEVEL` (default `INFO`), `LOG_FILE` (default `logs/<component>.jsonl`).

Dependencies: SLF4J 2.0.16, Logback 1.5.16, Jackson 2.18.3.

---

## 5. Module: `mock-client/` — headless FAF client

**Entry point:** `com.faforever.testharness.client.Main`

The mock-client is the orchestrator process for one simulated FAF user. In its final form it will:

1. Parse config (CLI / env / file).
2. Initialize structured logging.
3. Authenticate to the lobby server over WebSocket using OAuth.
4. Drive a finite state machine (idle → in-queue → matched → game-running → teardown).
5. Spawn `faf-ice-adapter` and `mock-game` as subprocesses, with their stdout/stderr piped into the JSONL log.
6. Proxy GPGNet messages between lobby/ICE and mock-game.
7. Tear down cleanly on game-end or fault.

### What's implemented today

The **configuration layer is complete and tested** (commit 2dc73f5, WBS 3.1.5.1). The rest of the lifecycle is not yet wired.

```
mock-client/src/main/java/com/faforever/testharness/client/
├── Main.java                          ← logs "Mock client started"; loads config
├── config/
│   ├── MockClientCli.java             ← Picocli @Command record, ~15 @Option fields
│   ├── MockClientConfig.java          ← immutable validated config record
│   ├── ConfigLoader.java              ← layered resolution: defaults → file → env → CLI
│   ├── LayeredDefaultProvider.java    ← custom Picocli provider; env convention
│   │                                    FAF_MOCK_CLIENT_<UPPER_SNAKE_CASE>
│   └── package-info.java
└── package-info.java
```

Resolution order (later wins):

1. Hard-coded defaults
2. JSON config file (path passed via `--config`)
3. Environment variables (`FAF_MOCK_CLIENT_*`)
4. CLI flags

Config errors exit with code `2`, distinguishing them from runtime failures (which will use other codes).

Dependencies: `shared`, Picocli 4.7.6, JUnit 5.

### What's still to come

- Lobby WebSocket client + OAuth flow
- Subprocess launcher for ICE adapter + mock-game (using `ProcessOutputLogger`)
- The lifecycle FSM (states and transitions sketched in `documentation/research/state-diagram.md`, including failure states added in commit 5f067e1)
- GPGNet proxy between ICE adapter and mock-game

---

## 6. Module: `mock-game/` — headless game process

**Entry point:** `com.faforever.testharness.game.Main`

**Status: stub.** Today `Main` only initializes logging and prints `Mock game started`.

### What it will do

Per `documentation/task-desc.md` §1.2 and the GPGNet spec at `documentation/research/gpgnet-format-spec.md` (commit 6c0fdb0, WBS 2.2.2):

- Parse CLI args passed by mock-client (UDP port, player IDs, session token, etc.).
- Open a TCP server on the GPGNet port and accept the ICE adapter's connection.
- Speak the **GPGNet binary wire format** — length-prefixed frames carrying typed args (int32, string, etc.) and a command name. The full byte-level spec, derived from `java-ice-adapter`, `faf-pioneer`, and the FA Lua sources, lives in `documentation/research/gpgnet-format-spec.md`.
- Run a deterministic heartbeat / tick loop so simulation time is reproducible.
- Handle init → run → teardown lifecycle.

```
mock-game/src/main/java/com/faforever/testharness/game/
├── Main.java
└── package-info.java
```

Dependencies: `shared`, JUnit 5.

---

## 7. `faf-test-harness/` — future orchestrator

The directory exists but is **not** included in `settings.gradle` and contains no Java source. It is reserved for the top-level orchestrator that will drive multiple `mock-client` instances together to compose multi-player scenarios (2–8 simulated clients), inject network faults, and assert lobby/ICE behavior. This is Phase 4 work.

---

## 8. Documentation tree

`documentation/` is where the project's "design memory" lives. Most current activity in the repo has been in here.

| Path | Contents |
|---|---|
| `project-spec.md` | Problem statement and solution outline |
| `task-desc.md` | Phase-by-phase task breakdown; source of WBS numbers |
| `libraries.md` | Library survey |
| `diagrams/architecture.md` | High-level component diagram (WBS 2.2.4) |
| `diagrams/sequence-full-session.md` | End-to-end login → game session sequence |
| `diagrams/README.md` | Index for the diagrams folder |
| `research/project-briefing.md` | Executive summary of the FAF mocking problem |
| `research/gpgnet-format-spec.md` | **Authoritative byte-level GPGNet wire-format spec** (WBS 2.2.2) |
| `research/lobby-protocol-spec.md` | Lobby WebSocket / REST auth & matchmaking |
| `research/state-diagram.md` | Mock client FSM with failure states (WBS 2.2.5) |
| `meetings/` | Notes from 10/12/19 Mar 2026 — WBS structure, schedule, roles |
| `operations/` | Cost, schedule, team-roles |
| `needs-answering/` | Open questions for supervisor / developers |

The two specs that drive future implementation are `gpgnet-format-spec.md` (mock-game) and `state-diagram.md` (mock-client lifecycle).

---

## 9. Build, CI, and conventions

**Gradle** (`build.gradle`, `settings.gradle`)

- Java 21 toolchain applied to all subprojects.
- Checkstyle (`config/checkstyle/checkstyle.xml`) + Spotless with Google AOSP formatting.
- Per-module dependencies declared in each subproject's own `build.gradle`.

**CI** (`.github/workflows/ci.yml`)

- `build` job: `gradle build`, tests, `checkstyleMain`, `spotlessCheck`.
- `dependency-submission` job: feeds Dependabot.
- Required status checks on `main`.

**CONTRIBUTING.md** codifies:

- Branch prefixes: `feature/`, `bugfix/`, `research/`, `docs/`, `chore/`.
- **Conventional Commits**, with the WBS id in brackets, e.g. `feat(shared): add logging framework [2.3.6]`.
- Squash-merge to `main` so `git log main` is a WBS-indexed changelog.
- JavaDoc requirements; Spotless/Checkstyle must pass before merge.
- PR template enforces the review and CI checklist.

### WBS numbering

Commits and PRs are tagged with hierarchical Work Breakdown Structure ids:

- **Phase 2 — Research & Framework** (`2.x`): e.g. `2.2.2` GPGNet spec, `2.2.4` component diagrams, `2.2.5` FSM, `2.3.1` dev conventions, `2.3.6` logging, `2.3.8` JavaDoc/Checkstyle.
- **Phase 3 — Implementation** (`3.x`): e.g. `3.1.5.1` mock-client config loader.
- **Phase 4 — Integration**: future, will live under the `faf-test-harness/` orchestrator module.

The scheme is defined in `documentation/task-desc.md` and was approved in the 19 Mar 2026 meeting (`documentation/meetings/`).

---

## 10. Current status, at a glance

| Component | Status |
|---|---|
| `shared/logging` — structured JSON logging | ✅ implemented |
| `mock-client` — config loader (Picocli, 4-level resolution) | ✅ implemented & tested |
| `mock-client` — lifecycle FSM | ⏳ planned (Phase 3) |
| `mock-client` — lobby WebSocket + OAuth | ⏳ planned (Phase 3) |
| `mock-client` — subprocess launcher / GPGNet proxy | ⏳ planned (Phase 3) |
| `mock-game` — CLI args | ⏳ stub |
| `mock-game` — GPGNet TCP server (spec in hand) | ⏳ stub |
| `mock-game` — heartbeat / tick loop | ⏳ stub |
| `faf-test-harness/` orchestrator | ⏳ placeholder (Phase 4) |
| Documentation — architecture, FSM, GPGNet spec | ✅ Phase 2 complete |
| Build / CI / conventions | ✅ stable |

Recent trajectory (last ~15 commits): heavy investment in research, specs, and infrastructure (logging framework, Checkstyle/Spotless, JavaDoc rules, config loader). The foundation is solid; the next work is the mock-client FSM and subprocess control, then the mock-game network layer using the GPGNet spec already on disk.

---

## 11. How it will work end-to-end (target flow)

1. CI or a developer launches `mock-client` with a config (CLI/env/file).
2. mock-client authenticates to the lobby server, joins a queue, gets matched.
3. mock-client spawns `faf-ice-adapter` and `mock-game`, piping their output through `ProcessOutputLogger` into JSONL logs.
4. ICE adapter and mock-game perform the GPGNet handshake. Mock-game runs a deterministic tick loop.
5. ICE negotiates with the peer's adapter; the "game" runs to a scripted end condition.
6. mock-client tears down the subprocesses, closes the lobby connection, and exits with a status code that CI can assert on.
7. JSONL logs from all components are available as test artifacts and can be diffed across runs.

That target flow is what the modules above are progressively being built toward.
