# Component Isolation Testing (WBS 4.2.3)

When an end-to-end run fails, you need to reproduce one component's behaviour **alone** to
localise the fault. The architecture already gives you the isolation: every component runs as its
own process, every seam has a scripted double, and diagnostic subcommands exist for the subprocess
paths. This document is the debugging procedure that turns those paths into a fault-localisation
walk, with the exact command or Gradle filter for each and the result observed when it was run.

There is nothing to reuse from upstream: `java-ice-adapter` ships **no** test harness of its own —
only JavaFX debug/info windows a headless environment cannot use (see
[`ice-adapter-setup.md`](ice-adapter-setup.md) — "Headless caveat"). **Our scripted doubles are the
isolation tooling.** Each speaks a wire protocol captured from `faf-server`, `java-ice-adapter`, and
the GPGNet research specs, so a pass against a double is evidence about the **real seam**, not about
the double.

> **How the doubles stay honest.** The doubles are `src/test`-scoped only — no new binary, no new
> subcommand, nothing ships to a consumer. `ice-smoke` remains a stub returning
> `NOT_IMPLEMENTED` (exit `64`); this card adds no CLI behaviour.

## How to use this document

Walk the rows from **broadest to narrowest**. A failing full-stack run (row 7) is localised by
peeling one seam at a time:

1. Full stack fails (row 7). Which seam?
2. **Client ↔ lobby** wrong? → row 1. The `ScriptedWebSocketServer` proves the transport with no
   network; the live smoke test proves the real lobby.
3. **Client ↔ adapter (RPC)** wrong? → row 2 (the seam) and row 4 (`launch-ice` — the adapter
   subprocess alone, no RPC).
4. **Game ↔ adapter (GPGNet)** wrong? → row 3 (the seam) and row 5 (`launch-game` — the game
   subprocess alone).
5. **Game traffic (UDP)** wrong? → row 6.

Each **offline** row runs against an in-process double: fully deterministic, no network, no external
binary — run it first. Each **live** row adds the real peer and therefore needs setup and network;
it is the confirmation step once the offline row is green.

## The matrix

| # | Component / seam | Offline path (double + owning test + filter) | Live path (command / subcommand) | A pass proves | Logs land |
|---|---|---|---|---|---|
| 1 | **Client ↔ lobby** (WebSocket) | `ScriptedWebSocketServer` — `LobbyConnectionTest`.<br>`./gradlew :mock-client:test --tests '*LobbyConnectionTest'` | `LobbyConnectionLiveSmokeTest` (`@Tag("integration")`).<br>`./gradlew :mock-client:integrationTest --tests '*LobbyConnectionLiveSmokeTest'` | WS upgrade + text-frame round-trip, clean/abrupt close handling, against the captured `faf-server` lobby protocol. Live: the real lobby accepts the transport and the auth handshake yields a terminal reply. | `logs/mockclient.jsonl` (+ console); JUnit report `mock-client/build/reports/tests/test/` |
| 2 | **Client ↔ adapter** (JSON-RPC/TCP) | `ScriptedJsonRpcServer` — `IceAdapterConnectionTest`.<br>`./gradlew :mock-client:test --tests '*IceAdapterConnectionTest'` | `IceAdapterConnectionLiveSmokeTest` (3.1.4.3, `@Tag("integration")`).<br>`./gradlew :mock-client:integrationTest --tests '*IceAdapterConnectionLiveSmokeTest'` | Newline-framed JSON-RPC request/response, id correlation, disconnect mechanics against the captured `java-ice-adapter` protocol. Live: the real adapter binds RPC and answers `status`. | `logs/mockclient.jsonl`; adapter child tagged `[ICEAdapter]` also under `logs/ice-adapter/` |
| 3 | **Game ↔ adapter** (GPGNet/TCP) | `ScriptedGpgNetServer` — `GpgNetConnectionTest`.<br>`./gradlew :mock-game:test --tests '*GpgNetConnectionTest'` | `GpgNetConnectionLiveSmokeTest` (3.2.2.4, `@Tag("integration")`) — **lands with 3.2.2.4**.<br>`./gradlew :mock-game:integrationTest --tests '*GpgNetConnectionLiveSmokeTest'` | GPGNet binary frame codec round-trip (well-formed and malformed/truncated) against the captured GPGNet wire format. Live: the real adapter's GPGNet endpoint accepts and answers frames. | `logs/mockgame.jsonl` |
| 4 | **Adapter alone** (subprocess) | `LaunchIceCommandTest`.<br>`./gradlew :mock-client:test --tests '*LaunchIceCommandTest'` | `launch-ice` subcommand vs. the real jar — the [R74 Quick start](ice-adapter-setup.md#quick-start-clean-checkout). Requires R74 setup + network. | The subprocess launch → output-capture → SIGTERM-reap plumbing for the adapter, with no lobby and no FSM. Live pass: adapter spawns, RPC socket opens, `status` answers, clean teardown (exit `0`). | `logs/mockclient.jsonl`; adapter `[ICEAdapter]` + `logs/ice-adapter/` |
| 5 | **Game alone** (subprocess) | `LaunchGameCommandTest`.<br>`./gradlew :mock-client:test --tests '*LaunchGameCommandTest'` | `launch-game` subcommand vs. the built `mock-game` binary (command below). | The subprocess plumbing for `mock-game`, with no lobby, FSM, or adapter. Pass condition: a `RUNTIME` (`70`) coded exit — see the note under the row. | `logs/mockgame.jsonl` (child `[MockGame]`) + `logs/mockclient.jsonl` (launcher) |
| 6 | **UDP game-traffic seam** | Sender `GameUdpSenderTest` (3.2.2.5) — **lands with 3.2.2.5**.<br>`./gradlew :mock-game:test --tests '*GameUdpSenderTest'`<br>Receiver `GameUdpReceiverTest` (3.2.2.6 / #213) — **lands with #213**.<br>`./gradlew :mock-game:test --tests '*GameUdpReceiverTest'` | No standalone live UDP test — the UDP seam to the adapter's `--lobby-port` is exercised inside a full orchestrated session (row 7). | Sender: per-peer datagram emission at the tick cadence with independent, incrementing sequences. Receiver: inbound datagram decode. Together: the game↔adapter UDP path. | `logs/mockgame.jsonl` |
| 7 | **Full stack** (end-to-end) | Orchestrated full-session test (3.1.2.7) — **lands with 3.1.2.7**.<br>`./gradlew :mock-client:integrationTest --tests '*FullSession*'` *(intended filter; name finalised by 3.1.2.7)* | `run` end-to-end demo — the `lobby-connect-idle` demo ([demos/README.md](../demos/README.md), WBS 3.1.1.4).<br>`./gradlew :mock-client:run --args="run --config mock-client.json"` | The components wired together against the live environment. Today's `run` demo proves connect → auth → `welcome` → idle ≥ 5 min → clean Ctrl-C. The 3.1.2.7 test will prove the orchestrated client→adapter→game session automatically. | `logs/mockclient.jsonl` |

## Execution record

Every row was exercised on a dev machine as part of this card. Environment: **macOS**, **JDK 21**
(`21.0.6`, `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`), **2026-08-04**.

| # | Row | Ran here? | Result |
|---|---|---|---|
| 1 | Client ↔ lobby — offline (`LobbyConnectionTest`) | ✅ | **11 passed, 0 failed** |
| 1 | Client ↔ lobby — live (`LobbyConnectionLiveSmokeTest`) | ⏭️ | Not run here — requires network + optional `.secrets/refresh_token.txt`; self-skips off-net |
| 2 | Client ↔ adapter — offline (`IceAdapterConnectionTest`) | ✅ | **16 passed, 0 failed** |
| 2 | Client ↔ adapter — live (`IceAdapterConnectionLiveSmokeTest`) | ⏭️ | Not run here — requires the real `faf-ice-adapter.jar` (R74) + network; self-skips when the jar is absent |
| 3 | Game ↔ adapter — offline (`GpgNetConnectionTest`) | ✅ | **10 passed, 0 failed** |
| 3 | Game ↔ adapter — live (`GpgNetConnectionLiveSmokeTest`) | ⛔ | Not on main — lands with 3.2.2.4; then requires the real adapter jar (R74) + network |
| 4 | Adapter alone — offline (`LaunchIceCommandTest`) | ✅ | **3 passed, 0 failed** |
| 4 | Adapter alone — live (`launch-ice`) | ⏭️ | Not re-run here — requires R74 setup + network. Verification record in [`ice-adapter-setup.md`](ice-adapter-setup.md#verification-record-2026-06-17-jdk-21011) (adapter spawn → `status` → clean teardown, 2026-06-17) |
| 5 | Game alone — offline (`LaunchGameCommandTest`) | ✅ | **3 passed, 0 failed** |
| 5 | Game alone — live (`launch-game`) | ✅ | **Exit `70` (RUNTIME)** — see the recorded output below |
| 6 | UDP sender — offline (`GameUdpSenderTest`) | ⛔ | Not on main — lands with 3.2.2.5. Filter verified against the branch |
| 6 | UDP receiver — offline (`GameUdpReceiverTest`) | ⛔ | Not on main — lands with #213 (3.2.2.6) |
| 7 | Full stack — automated (3.1.2.7) | ⛔ | Not on main — lands with 3.1.2.7 |
| 7 | Full stack — live (`run` / `lobby-connect-idle` demo) | ⏭️ | Not run here — requires `.secrets/refresh_token.txt` + the `faf-uid` binary + network. Committed evidence: [`demos/lobby-connect-idle.log`](../demos/lobby-connect-idle.log) |

Legend: ✅ ran and passed · ⏭️ not run here (needs R74 setup and/or network — see the row) · ⛔ not
on `main` yet (intended filter recorded).

### Row 5 — `launch-game` recorded output (re-recorded 2026-08-24, after 3.2.5.1)

Build the two binaries, then point the subcommand at the built `mock-game`:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew :mock-client:installDist :mock-game:installDist

mock-client/build/install/mock-client/bin/mock-client launch-game --duration-seconds=5 \
  --mock-game-binary-path="$PWD/mock-game/build/install/mock-game/bin/mock-game" \
  --lobby-websocket-url=wss://ws.faforever.xyz \
  --oauth-token-url=https://hydra.faforever.xyz/oauth2/token \
  --oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth \
  --oauth-redirect-uri=http://127.0.0.1 --oauth-scopes="openid offline lobby" \
  --oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8 \
  --unique-id=00000000-0000-0000-0000-000000000000 \
  --oauth-refresh-token-file=dummy-unused-by-launch-game
```

The OAuth flags are required by config validation but unused by `launch-game` (it only spawns the
game); any syntactically valid placeholders work — same convention as `launch-ice`. Observed:

```
[MockClient] Launching mock-game: .../mock-game --gpgnet-port 7237 --lobby-port 7238 --player-id 1 --player-login mock-client --game-uid 0 --launch-delay-seconds 5
[MockClient] mock-game started, pid=30207
[MockGame]   mock game started: playerId=1 login=mock-client gameUid=0 gpgNetPort=7237 lobbyPort=7238 launch=auto after 5s
[MockGame]   Created StateMachine with initial state INITIALIZING and policy IGNORE
[MockGame]   [WARN] could not connect to GPGNet server at 127.0.0.1:7237: GPGNet server not reachable at 127.0.0.1:7237 after 20 attempts
[MockGame]   shutting down mock game
[MockGame]   mock game shutdown complete
[MockGame]   mock game finished: status=SERVER_NOT_CONNECTED, exit code 70
[MockClient] [ERROR] mock-game exited on its own before the 5s run window; exit code 70
# process exit: 70 (RUNTIME)
```

**Pass condition for this row is a `RUNTIME` (`70`) coded exit together with the two launch lines
above, not the exit code alone.** `launch-game` returns `RUNTIME` whenever the game exits before the
run window elapses, and also when the binary cannot be launched at all. Since 3.2.5.1 landed,
`mock-game` runs its bounded adapter-connect window (about two seconds) and then exits `70` of its
own accord, so the child's exit code and the subcommand's now agree — where previously the game was
a stub that exited `0` and only `launch-game` supplied the `RUNTIME`. The documented pass condition
is unchanged.

## Reproducing the offline runs

All offline rows are network-free and deterministic. Scope Gradle to the relevant module — the
`shared` module has a pre-existing `/bin/true`-style test that fails on macOS
(`SubprocessManagerStartTest > fastExitingChildDoesNotLeakIntoRegistry`, tracked as #227), unrelated
to any row here; run per-module test filters rather than the whole `build` when validating on a Mac.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

# Rows 1, 2, 4, 5 (mock-client offline)
./gradlew :mock-client:test \
  --tests '*LobbyConnectionTest' \
  --tests '*IceAdapterConnectionTest' \
  --tests '*LaunchIceCommandTest' \
  --tests '*LaunchGameCommandTest'

# Row 3 (mock-game offline)
./gradlew :mock-game:test --tests '*GpgNetConnectionTest'
```

Per-class report: `mock-client/build/reports/tests/test/index.html` and the matching
`mock-game/...`; machine-readable results under `<module>/build/test-results/test/`.

## Live rows — setup and network

The live rows (2's live smoke, 4's `launch-ice`, 3's `GpgNetConnectionLiveSmokeTest`, and 7's `run`
demo) need the **real** `faf-ice-adapter` jar and/or the FAF `.xyz` network:

- **Adapter jar:** provision per [`ice-adapter-setup.md`](ice-adapter-setup.md) (R74) —
  `./gradlew downloadIceAdapter`, or set `FAF_ICE_ADAPTER_JAR`. The `integration`-tagged tests
  self-skip cleanly when no jar resolves, so they merge green before the jar is provisioned.
- **Network:** the lobby live smoke test needs network access to the FAF test lobby and self-skips
  (does not fail) when it is unreachable. The `run` demo additionally needs a bootstrapped
  `.secrets/refresh_token.txt` and the `faf-uid` binary — see
  [`demos/README.md`](../demos/README.md).

None of these were run on this (off-network, un-provisioned) machine; their documented pass
conditions are recorded in the matrix and in the linked docs, and are not faked here.

## Related documents

- [`ice-adapter-setup.md`](ice-adapter-setup.md) — R74: provisioning and running the real adapter
  headless; the source of truth for rows 2 (live), 3 (live), and 4 (live).
- [`demos/README.md`](../demos/README.md) — the `lobby-connect-idle` end-to-end demo, closest live
  evidence for row 7 until 3.1.2.7 lands.
- [`research/json-rpc-spec.md`](../research/json-rpc-spec.md),
  [`research/gpgnet-format-spec.md`](../research/gpgnet-format-spec.md),
  [`research/lobby-protocol-spec.md`](../research/lobby-protocol-spec.md) — the wire protocols the
  doubles replay, which is what makes an offline pass evidence about the real seam.
