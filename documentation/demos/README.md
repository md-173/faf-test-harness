# Demos

Sprint-review evidence for the Mock Client. Each demo is a short recording, log
transcript, or screenshot set that proves a deliverable works end to end against
a real environment, captured by hand and committed here.

| Demo | WBS | Proves | Artifact |
|------|-----|--------|----------|
| `lobby-connect-idle` | 3.1.1.4 | `run` connects, authenticates, logs the player id, and sits idle | ✅ [`lobby-connect-idle.log`](lobby-connect-idle.log) (live capture, 2026-07-14, on the FSM-integrated code path) |
| `client-game-lifecycle` | 3.1.2.7 | the client launches the real adapter and the real mock game, the handshake completes, the FSM runs the session on real signals, and teardown leaves nothing running | ▶️ live test, run on demand — see below |
| `multi-peer-session` | 4.3.1, 4.3.2, 4.3.3 | two, three and four clients host and join the same game through the live lobby, ICE candidates relay across it, every adapter reports a link to every other peer, and every line is attributable to its instance | ▶️ live test, run on demand. 4.3.1 verified 2026-08-25 (`test` ↔ `Foo`); 3 and 4 peers verified 2026-09-15, three passes (`test`, `Foo`, `Tagada`, `Paralon` against `ws.faforever.xyz`); see below |

---

## `multi-peer-session`: host, join, full mesh (WBS-4.3.1, 4.3.3)

Two to four Mock Clients on one machine, each with its own lobby account, port
set, ICE adapter and mock game, complete a host/join **through the live lobby**
and every adapter reports a link to every other peer: one connection at two
players, three at three, six at four. The whole exchange (`game_host` /
`game_join`, `JoinGame`, `ConnectToPeer`, and every ICE candidate) crosses the
real server; the only value passed in-process is A's game uid, which is what an
operator would read off A's `game launch:` line.

`MultiPeerSessionLiveTest` runs three cases back to back: one, two and three
joiners. Three players are where faf-server's peer-to-peer path starts. The host
is told `offer=true` for every joiner (`connect_to_host`), a joiner is told
`offer=true` for every earlier joiner, and each earlier joiner `offer=false` for
it (`connect_to_peer`). The test asserts exactly that set per peer. The host
advertises the game as `friends` visibility; joining by uid is unaffected,
because `command_game_join` never checks visibility.

### Prerequisites

Everything the 3.1.2.7 demo needs (adapter jar, mock-game distribution), plus:

3. **The `faf-uid` binary** at `./faf-uid`. The lobby's policy server rejects a
   placeholder `unique_id`. Same download as the `lobby-connect-idle` demo
   below. Override with `FAF_UID_BINARY=/path/to/faf-uid`.
4. **One seeded account per peer, with a refresh token each**, at
   `.secrets/refresh_token.txt` (A, hosts), `_b.txt`, `_c.txt` and `_d.txt`
   (joiners B to D). Override with `FAF_REFRESH_TOKEN_A` to `FAF_REFRESH_TOKEN_D`.
   A case skips when a joiner it needs has no token. One account cannot host
   and join its own game, and one account in two peers signs the first out, so
   every account must be distinct; bootstrap each exactly as the first (see
   `lobby-protocol-spec.md` §2 and the `lobby-connect-idle` prerequisites below).
   **Every file used is rewritten on every run**, because Hydra rotates the
   refresh token on use. Never run two sessions on the same accounts at once.

The endpoint defaults to `wss://ws.faforever.xyz`, the FAF test lobby, which is
public: no VPN or allowlist is needed. Set `FAF_LOBBY_URL` to point elsewhere,
and `FAF_ICE_ADAPTER_JAR`, `FAF_MOCK_GAME_BINARY` and `FAF_UID_BINARY` to use
binaries outside the repository. The test skips when the lobby does not accept a
TCP connection within 3 s; to check by hand:

```bash
timeout 5 bash -c 'cat < /dev/null > /dev/tcp/ws.faforever.xyz/443' \
  && echo REACHABLE || echo UNREACHABLE
```

Any missing prerequisite **skips** the case rather than failing it, and one
line lists everything it wanted. A skip is not a pass: check for a
`[4.3.1] skipping` line before believing a green run. All prerequisite probing is
in `missingPrerequisites`, the one place a CI run would turn skips into
failures.

### Run

```bash
./gradlew :mock-client:integrationTest --tests '*MultiPeerSessionLiveTest*' --rerun
```

`--rerun` is not optional, for the reason given under 3.1.2.7. The acceptance
bar is three consecutive passes:

```bash
for i in 1 2 3; do
  ./gradlew :mock-client:integrationTest --tests '*MultiPeerSessionLiveTest*' --rerun \
    || { echo "run $i FAILED"; break; }
done
```

No game auto-launches its match (`--mock-game-launch-delay-seconds=-1`), so a
case costs roughly its sessions' setup rather than a simulated match: about
20-40 seconds per case, and under two minutes for all three. Every wait is also
bound by a 420 s per-case deadline and fails naming the peer and stage; the
600 s JUnit timeout per case only covers a hung teardown, so all three cases
take at most 30 minutes.
That flag is load-bearing: faf-server accepts a `game_join` only while the game
is in `GameState.LOBBY` and drops it out of that state as soon as the host
reports `GameState Launching`, so a host on the default 5 s timer makes itself
unjoinable while the joiner is still booting two JVMs.

### What to look for in the logs

All clients run in one JVM, so their lines interleave. Each is labelled with its
instance, `A` (the host) to `D`, on the console as a segment after the
component, `[2026-09-14 20:28:30.899] [Unknown] [A] [INFO ] state entry: CONNECTING`,
and as the `instance` field in JSONL. The label covers the client's own lines on
every thread it owns and its captured adapter and game output; each game also
writes its own `logs/mockgame-<label>.jsonl`. Filter on one label to read one
peer. The JSONL lands under `mock-client/logs/`: `test-harness.jsonl` for the
clients with their captured adapter and game output, and `mockgame-A.jsonl` to
`mockgame-D.jsonl` from the games themselves. Repeated cases append to the same
files; each case starts with a `case: N peers, run <uuid>` line, and the uuid is
also in the hosted game's title. The order below is the one to read for, shown
for A and a joiner B; C and D repeat B's rows.

| Stage | Log line | Source |
|-------|----------|--------|
| A authenticated | `session ready: id=<idA> login=<loginA>` | `WelcomeStateSync` |
| A hosts | `Sending game_host for title=faf-test-harness 4.3.1 <uuid>` | `MockClientLifecycle` |
| A's session up | `game launch: uid=<uid> mod=faf name=…`, then `state entry: STARTING_GAME` | `MockClientLifecycle` |
| A's game in the lobby | `Received GPGNet message: GameState Idle` → `GameState Lobby` | `[ICEAdapter]` |
| A is hosting | `Sent GPGNet message: HostGame scmp_007`, then `state entry: HOSTING` | `[ICEAdapter]` / `MockClientLifecycle` |
| B authenticated | `session ready: id=<idB> login=<loginB>` | `WelcomeStateSync` |
| B joins | `Sending game_join for uid=<uid>`, then `game launch: uid=<uid> …` | `MockClientLifecycle` |
| B is joining | `state entry: JOINING` | `MockClientLifecycle` |
| A told about B | `peer connect: login=<loginB> id=<idB> offer=true` | `MockClientLifecycle` |
| C told about B, B about C (3+ players) | on C: `peer connect: login=<loginB> id=<idB> offer=true`; on B: `peer connect: login=<loginC> id=<idC> offer=false` | `MockClientLifecycle` |
| Candidates crossing | `Sending ICE RPC request {…"method":"iceMsg"…}` on both sides | `IceAdapterConnection` |
| Peer states moving | `peer ice: local=<id> remote=<id> state=gathering` → `awaitingCandidates` → `checking` → `connected` | `IceEventLogger` |
| **The verdict** | `peer connected: local=<idA> remote=<idB> connected=true`, and the mirror image on B; one pair per link, so 2, 6 or 12 lines at two, three or four players | `IceEventLogger` |
| Teardown | `state entry: TERMINATED` → `session teardown complete`, once per peer | `MockClientLifecycle` |

`offer=true` on A is the server's doing, not ours: `connect_to_host` in
faf-server's `gameconnection.py` makes the side already in the lobby the ICE
initiator. The `peer ice` states are informational — `completed` never arrives
(adapter 3.3.14 has no `setState(COMPLETED)` call site), which is why the test
asserts on `onConnected` and not on a "final" ICE state.

The expected-noise entries under 3.1.2.7 (telemetry `WebsocketNotConnected`,
the adapter's EOF-at-shutdown ERROR) apply here too, once per client.

> **`dropping IceMsg … not a JSON object` is not expected noise — it is the
> failure this test was built to catch.** It means candidates are being
> double-encoded on the way out, so no peer ever receives one and ICE cycles
> `gathering → awaitingCandidates → disconnected` until the budget runs out.
> The first 4.3.1 run failed exactly this way: json-rpc-spec.md documented the
> ICE payload as an object, the shipped adapter sends and expects a JSON
> *string*, and `IceSignalRelay` had implemented the spec. Both halves of that
> relay agreeing on the wrong shape is invisible to any single-client test. See
> the payload-shape correction in json-rpc-spec.md §5.

### Acceptance criteria → evidence

- Every adapter reports `onConnected` true for every other lobby-assigned id,
  through the live lobby, with the empty ICE server list, on one machine → the
  `awaitFullMesh` checkpoint, which asserts each adapter's own id and waits for
  the latest verdict per remote peer under one deadline.
- The same mesh is visible from log output alone, and every line is
  attributable → `assertInstanceLabels`: every adapter and game line carries a
  label, each peer has labelled client, adapter and game lines, no labelled line
  names another peer's id as its own, and each peer's labelled `peer connected`
  lines name every other peer.
- Both offer directions → `assertOfferDirections`, the exact per-peer set of
  `ConnectToPeer` offers faf-server's protocol predicts.
- No port collision → `assertDistinctPorts` per case, and repeated runs.
- Game traffic both ways on every pair (4.3.2) → `awaitPeerTraffic`.
- Every session tears down and nothing survives → the shutdown of each
  lifecycle, then the descendant-process sweep, which runs after a failed case
  too so its survivors are never blamed on the next case.
- A four-player game has exactly two teams and reports one `GameResult` per army
  → not observable here, since no match launches and faf-server validates teams
  only in `Game.launch()`; covered by mock-game's `LifecycleSetupTest`.
- Repeatable → three consecutive passes with the loop above.

---

## `client-game-lifecycle` — launch, handshake, play out, tear down (WBS-3.1.2.7)

The sprint's integration milestone, and the sprint demo script. One command drives a whole
session: the Mock Client spawns the **real** `faf-ice-adapter` and the **real** mock game as
subprocesses, the game completes its GPGNet handshake with the adapter, the FSM walks
`IDLE → STARTING_GAME → HOSTING → PLAYING → TERMINATED` on frames the game actually sent, the
game plays out its match and self-exits 0, and teardown leaves no process behind.

It is a live-tagged test rather than a CLI run, because the checkpoints have to be asserted, not
eyeballed: [`ClientGameLifecycleLiveTest`](../../mock-client/src/test/java/com/faforever/testharness/client/state/ClientGameLifecycleLiveTest.java).
Excluded from `./gradlew :mock-client:test`, since it needs the adapter binary. CI runs it on every
pull request, in `ci.yml`'s `live-tests` job, which downloads the pinned adapter first.

**Deliberately lobby-independent.** Client↔lobby integration is already proven (see the demo
above), so this one isolates the previously-unproven client↔adapter↔game seam: it posts the three
lobby-side triggers itself (`WelcomeReceived` with a fabricated identity, `LaunchGame`, `HostGame`)
instead of waiting on the live lobby's `game_launch`. Nothing else in the run is faked. The
lobby-driven full path is the two-peer milestone's job (4.3.1). No network access is required, and
no credentials — unlike every other live test here, this one runs offline.

### Prerequisites

1. **The adapter jar.** `./gradlew downloadIceAdapter` puts the pinned `-nojfx` jar at
   `./faf-ice-adapter.jar`; see [`ice-adapter-setup.md`](../operations/ice-adapter-setup.md) (R74).
   Override with `FAF_ICE_ADAPTER_JAR=/path/to/jar` if you keep it elsewhere.
2. **The mock-game distribution.** Built automatically — `:mock-client:integrationTest` depends on
   `:mock-game:installDist`. Override with `FAF_MOCK_GAME_BINARY=/path/to/bin/mock-game`.

If either binary is missing the test **skips** rather than fails, printing which one and how to get
it. A skip is not a pass — check for the `[3.1.2.7] skipping` line before believing a green run.

### Run

```bash
./gradlew :mock-client:integrationTest --tests '*ClientGameLifecycleLiveTest*' --rerun
```

> **`--rerun` is not optional.** Without it, a second invocation with no source changes prints
> `Task :mock-client:integrationTest UP-TO-DATE` and `BUILD SUCCESSFUL in 765ms` — a green build
> that executed nothing. `--rerun` is task-scoped (Gradle 7.6+); prefer it over `--rerun-tasks`,
> which also re-runs compile, checkstyle, spotless and `installDist` and so inflates the timing
> below.

Roughly **25 seconds**, most of it the mock game's own simulated match
(`Main.MATCH_DURATION`, 10 s) plus its lobby wait (`--launch-delay-seconds`,
5 s by default). The match duration has no flag; the lobby wait grew one in
WBS-4.3.1, and this test takes the default.

> The **45 s** recorded here previously was a measured figure, taken when
> `MATCH_DURATION` was 30 s. WBS-3.2.5.1-fix (#253) shortened it to 10 s, and
> the number above is that change applied to the same breakdown rather than a
> fresh measurement — the twenty seconds come off the match, and nothing else
> in the run was touched. Re-time it on the next live run and drop this note.

The acceptance bar is three consecutive passes:

```bash
for i in 1 2 3; do
  ./gradlew :mock-client:integrationTest --tests '*ClientGameLifecycleLiveTest*' --rerun \
    || { echo "run $i FAILED"; break; }
done
```

### What to look for in the logs

`showStandardStreams` is on for this task, so the captured `[ICEAdapter]` and `[MockGame]`
subprocess output is interleaved with the client's own lines. The client's own lines are tagged
`[Unknown]` rather than `[MockClient]` in this JVM — `LoggingSetup.configure` is only called from
`Main`, and a test does not go through it — so the "Source" column below names the emitting logger,
not the bracketed tag. Only `[ICEAdapter]` and `[MockGame]` appear as tags.

The FSM starts at `CONNECTING` (logged before the posted welcome) and the checkpoints then run in
this order:

| Stage | Log line | Source |
|-------|----------|--------|
| Adapter up, under the **session** identity | `Launching ICE adapter: … --id 9001 --login welcome-login` | `IceAdapterLauncher` |
| RPC transport up | `connected to ICE adapter JSON-RPC at 127.0.0.1:<port>` | `IceAdapterConnection` |
| Game up, same identity | `Launching mock-game: … --player-id 9001 --player-login welcome-login` | `MockGameLauncher` |
| | `state entry: STARTING_GAME` | `MockClientLifecycle` |
| Handshake | `Sent GPGNet message: CreateLobby 0 <port> welcome-login 9001 1` → `Received GPGNet message: GameState Idle` → `Received GPGNet message: GameState Lobby` | `[ICEAdapter]` |
| Host role taken | `Sent GPGNet message: HostGame scmp_007`, then `state entry: HOSTING` | `[ICEAdapter]` / `MockClientLifecycle` |
| Match live | `Received GPGNet message: GameState Launching`, then `state entry: PLAYING` | `[ICEAdapter]` / `MockClientLifecycle` |
| Result reported | `GameResult 1 victory 10` → `JsonStats` → `GameEnded`, **in that order** | `[ICEAdapter]` |
| Clean self-exit | `mock game finished: status=OK, exit code 0`, then `mock-game exited cleanly with exit code 0` | `[MockGame]` / `MockClientLifecycle` |
| Teardown | `state entry: TERMINATED` → `tearing down session` → `session teardown complete` | `MockClientLifecycle` |

> **The handshake lines look out of order, and are not.** `CreateLobby` is logged *before* the
> `Received … GameState Idle` that caused it. Upstream `GPGNetServer.processGpgnetMessage` (3.3.14)
> sends `CreateLobby` from inside the `Idle` handler and only logs `Received GPGNet message` at the
> *end* of the method, while `sendGpgnetMessage` logs at send time. Read it as "CreateLobby was the
> reply to Idle", not as the adapter speaking first.

> **`[MockGame]` output arrives in a lump.** The child's stdout is block-buffered, so game log lines
> can surface tens of seconds after they were emitted — a line stamped at `t+2s` may not appear
> until the game exits. On a run that fails *before* the game exits, the most recent `[MockGame]`
> lines may be missing from the log entirely; read the `[ICEAdapter]` frame log for what the game
> actually sent.

The identity values (`9001` / `welcome-login`) are the fabricated session identity, and are
deliberately unlike the config defaults the launchers would otherwise fall back on — seeing them in
both argvs is what proves WBS-3.1.2.9 propagation, and the test asserts it against the live
processes.

**Expected noise, not failures.** Two lines look alarming and are neither:

- `[ICEAdapter] … TelemetryDebugger - Error on sending message object: …
  WebsocketNotConnectedException` — the adapter phones home to
  `ice-telemetry.faforever.com` and 3.3.14 has no working off switch
  ([`ice-adapter-setup.md`](../operations/ice-adapter-setup.md)). Harmless.
- `[ICEAdapter] … Error while communicating with FA (input), assuming shutdown … EOFException` —
  this is the adapter noticing the game closed its GPGNet socket on the way out, i.e. the clean
  end, logged at ERROR by upstream.

### Acceptance criteria → evidence

- Launch, handshake, phases, clean end, teardown, repeatably (three runs) → the loop above.
- Every checkpoint asserted through a public seam → FSM `stateReached` futures, the R36
  `onGpgNetMessageReceived` fan-out, and the game-exit signal. No test hooks in production code.
- Fails loudly rather than hanging → every wait is bounded by a named constant and reports what it
  had observed; the class-level `@Timeout` is a last resort, not the mechanism.
- No orphans → the test walks this JVM's process descendants after teardown and fails on any
  surviving adapter or game.

---

## `lobby-connect-idle` — connect, authenticate, idle (WBS-3.1.1.4)

> **Superseded as the setup path.** For prerequisites, configuration, and the
> run command, follow
> [`documentation/operations/harness-runbook.md`](../operations/harness-runbook.md)
> instead — it sequences this material with the ICE adapter and
> component-isolation docs into one ordered path and resolves contradictions
> that existed across them. This section remains the sprint-review evidence
> record for this specific demo: the captured transcript, the
> acceptance-criteria mapping below, and how to capture a fresh recording.

One command brings the Mock Client up against the live test lobby, runs the full
handshake (`connect → ask_session → session → auth → welcome`), logs the
authenticated player id, and holds the connection idle on the ping/pong
heartbeat until interrupted. Captured transcript: [`lobby-connect-idle.log`](lobby-connect-idle.log).

### Endpoint

The working test lobby is **`wss://ws.faforever.xyz`** (the `.com→.xyz` swap of
prod `ws.faforever.com`). The previously-documented `lobby.faforever.xyz` is
unreachable in practice — see the Correction note in `lobby-protocol-spec.md`.
Confirm reachability first:

```bash
# expect: succeeds within ~1s
timeout 5 bash -c 'cat < /dev/null > /dev/tcp/ws.faforever.xyz/443' \
  && echo REACHABLE || echo UNREACHABLE
```

### Prerequisites

1. **A bootstrapped refresh token** at `.secrets/refresh_token.txt` (gitignored).
   `run` authenticates via the refresh-token **file** channel — the token is
   rotated and re-persisted on each use, which is why no literal token option
   exists. See `lobby-protocol-spec.md` §2 for the one-time bootstrap.
2. **The `faf-uid` binary.** The lobby's policy/anti-cheat server rejects a
   placeholder `unique_id` (the login ends in `{"command":"invalid"}`), so a real
   RSA-encrypted UID is required. Download the official binary for your platform
   from [FAForever/uid releases](https://github.com/FAForever/uid/releases) (it
   embeds the public key the server expects) and make it executable:
   ```bash
   curl -sSL -o faf-uid https://github.com/FAForever/uid/releases/download/v4.0.7/faf-uid
   chmod +x faf-uid          # faf-uid.exe (Windows) / faf-uid-macos also available
   ```
3. **A config file.** Copy the example and point it at the token file + binary:
   ```bash
   cp mock-client/mock-client.example.json mock-client.json
   # ensure: "oauthRefreshTokenFile": "./.secrets/refresh_token.txt"
   #         "uidBinaryPath":         "./faf-uid"
   ```
   Every endpoint and credential comes from this config — nothing is hardcoded.

### Run

```bash
./gradlew :mock-client:run --args="run --config mock-client.json"
```

Let it sit for **at least five minutes** to show the heartbeat keeps the
connection alive, then press **Ctrl-C** to close cleanly.

**Override chain (CLI > env > file).** Put a deliberately wrong URL in the config
file, then override it — the `lobby WebSocket connected: <url>` line shows which
source won:

```bash
# CLI flag wins over the (wrong) file value → connects to ws.faforever.xyz
./gradlew :mock-client:run --args="run --config mock-client.json \
  --lobby-websocket-url=wss://ws.faforever.xyz"

# env var wins over the file value (but loses to a CLI flag)
FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL=wss://ws.faforever.xyz \
  ./gradlew :mock-client:run --args="run --config mock-client.json"
```

### What to look for in the logs

Each transition is logged on its own line (console + `logs/<component>.jsonl`):

> This table is a reading aid for checking a demo run by eye. The
> machine-readable formats an automated harness parses (state entries,
> identity, peer connection state) are specified in `mock-client/README.md`
> § "Harness log contract", which is the authoritative contract.

| Stage | Log line (logger) |
|-------|-------------------|
| Transport up | `lobby WebSocket connected: wss://ws.faforever.xyz` (`LobbyConnection`) |
| UID generated | `generated unique_id via faf-uid (<n> chars)` (`LobbyHandshake`) |
| Authenticated | `lobby authenticated as login=<name>` (`LobbyHandshake`) |
| State hydrated | `session ready: id=<id> login=<name>` (`WelcomeStateSync`) |
| Idle | `mock client idle as player id=<id> login=<name>; press Ctrl-C to exit` (`RunCommand`) |
| Heartbeat | the connection stays up across ≥ 5 min — server `ping`s are auto-answered with `pong` (no per-ping line at INFO) |
| Clean shutdown | `shutdown signal received; tearing down session` (`RunCommand`) |

**No credential ever appears in a log line** — the JWT access token, the refresh
token, and the `faf-uid` UID blob are never logged; only the blob's length and
the server-supplied login/id are.

> **Exit code:** Ctrl-C / SIGTERM closes the socket via a JVM shutdown hook, then
> the process exits with the signal's conventional code (130 SIGINT / 143
> SIGTERM), not literally 0 — the close is clean with no error logs, which is
> what the teardown criteria care about. The hook sends the WebSocket close
> frame; the server's close echo can land after the JVM has halted, so a trailing
> `lobby WebSocket closed … LOCAL_CLOSE` line is not always flushed.

### Capturing the artifact

Capture **one** of the following and commit it next to this README:

- **asciinema** (preferred): `asciinema rec documentation/demos/lobby-connect-idle.cast`,
  run the command inside the recording, Ctrl-C, then `exit`.
- **Log transcript**: copy the run's console output (or `logs/<component>.jsonl`)
  to `documentation/demos/lobby-connect-idle.log` — as already committed here.
- **Screenshots**: the connect/auth/idle lines and the shutdown line, under
  `documentation/demos/lobby-connect-idle/`.

Scrub anything sensitive before committing (there should be none — credentials
are never logged — but double-check any pasted shell history for tokens).

### Acceptance criteria → evidence

- Connects, authenticates, receives welcome, logs the player id → the transition
  lines above (see the committed transcript).
- Stays idle ≥ 5 min without being dropped → timestamps ≥ 5 min apart with no
  reconnect/disconnect line in between.
- Ctrl-C closes the socket cleanly, no zombie process, no error logs at shutdown
  → the `shutdown signal received` line and a returned shell prompt.
- No credentials in any log line → inspect the transcript.
- Override chain works end to end → the `connected` URL matches the CLI/env
  override, not the file value.
