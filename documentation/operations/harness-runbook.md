# Harness Runbook: Setup and Single Session (WBS 7.2.1)

Everything a newcomer needs to run the Mock Client, in one ordered path. Two
audiences, in this order:

1. Someone embedding the mock game in another project's tests. **No FAF
   account, credentials, or network beyond localhost.** Start at
   [§2](#2-running-the-game-against-an-adapter-no-lobby-required).
2. Someone running a full client session against the live test lobby. Needs a
   FAF test account. Continue to [§3](#3-credentials) onward.

This document sequences and resolves material that already exists in
[`documentation/demos/README.md`](../demos/README.md),
[`mock-client/README.md`](../../mock-client/README.md),
[`ice-adapter-setup.md`](ice-adapter-setup.md), and
[`component-isolation.md`](component-isolation.md) — it is not a rewrite of
any of them, and it does not restate their field reference, log-line contract,
or developer conventions. Multi-peer sessions (two or more Mock Clients on one
box) are out of scope here; see the stub at [§9](#9-multi-peer-sessions-r79b).

## 1. Prerequisites

### Always needed (both audiences)

- **JDK 21.** The repo toolchain target ([`ice-adapter-setup.md`](ice-adapter-setup.md)):
  `build.gradle` pins every subproject's toolchain to language version 21,
  which governs compile/test/run regardless of which JDK starts the Gradle
  daemon. There is no foojay resolver configured, so Gradle cannot fetch a
  missing toolchain on its own — a machine with only a newer JDK installed
  fails with `Cannot find a Java installation ... matching:
  {languageVersion=21, ...}` / `Toolchain download repositories have not been
  configured`. Have a real JDK 21 on the machine; the vendor does not matter.
  §2's live commands have been run on two: an auto-detected local Temurin 21 on
  Windows (where the Temurin 25 also present was only ever the Gradle daemon's
  own JVM), and Ubuntu's OpenJDK 21 build on WSL2.
- **The Gradle wrapper.** No separate Gradle install; use `./gradlew` (or
  `gradlew.bat` on Windows) from the repo root throughout.
- **The pinned `faf-ice-adapter` jar**, fetched with its own step:

  ```bash
  ./gradlew downloadIceAdapter
  ```

  This is deliberately **not** part of `build` or `check` — it hits the
  network and is explained in full in [`ice-adapter-setup.md`](ice-adapter-setup.md).
  Run it once per clone; it verifies the existing jar's checksum and skips the
  download on a re-run. Lands at `./faf-ice-adapter.jar`, which is the
  launcher's default `--ice-adapter-binary-path`.

That is the complete list for the adapter-only path in §2. **Do not go
further down this list unless you are going to use the live lobby** — the
items below are pointless to obtain otherwise.

### Only needed for a live lobby session (§4 onward)

- **A FAF test account.** Test users on `*.faforever.xyz` share the password
  `foo` — see [§3](#3-credentials).
- **Its OAuth refresh token**, bootstrapped once through a browser (§3).
- **The `faf-uid` binary**, referenced by the `uidBinaryPath` config key. This
  is a **hard requirement** for a live session: the lobby's policy server
  rejects a placeholder `unique_id`, and the login ends in
  `{"command":"invalid"}` without it. There is no way around it for a real
  session — see §3 for how to obtain it.

## 2. Running the game against an adapter, no lobby required

This is the entry point for anyone consuming the release inside another
project's CI: no account, no OAuth, and — once §1's one-time
`downloadIceAdapter` has fetched the jar — no network beyond localhost, save
the adapter's own telemetry websocket, which 3.3.14 cannot be told to skip and
which fails harmlessly when it cannot connect (see the note on #236 below).

*Provenance. Every command in this section was re-executed against merged
`main` on **2026-09-01**, on WSL2 Linux (Ubuntu 24.04, OpenJDK 21.0.12 — the
distribution build, not Temurin) with `faf-ice-adapter` 3.3.14 — after 3.2.5.1
(#246) and 3.1.2.7 (#249) landed, so
the behaviour described below is the merged behaviour and not a projection of
it. The section was originally produced on **Windows 11** (Gradle daemon on
Temurin 25, compile/run on a local Temurin 21) on 2026-08-21; that run is kept
because the exit-code note below turns on the difference between the two
platforms. The one piece of evidence this section defers to —
[`component-isolation.md`](component-isolation.md) row 5 — was re-recorded in
the same 2026-09-01 pass.*

### Start here: `ice-smoke`

One command answers "is this harness working?" for a consumer with no FAF
account. It spawns the adapter, connects to its JSON-RPC port, sends one
request, connects to its GPGNet port and waits for the adapter to announce that
connection back over RPC, then tears everything down. Exit `0` means reachable;
anything else names the phase that failed.

```bash
./gradlew :mock-client:installDist
./gradlew downloadIceAdapter

./mock-client/build/install/mock-client/bin/mock-client ice-smoke \
  --ice-adapter-binary-path="$PWD/faf-ice-adapter.jar"
```

That is the entire invocation: no lobby URL, no OAuth values, no placeholders.
Run from the repo root, where `downloadIceAdapter` puts the jar on the default
path, even the binary flag is optional — `mock-client ice-smoke` alone passes.
It takes about two seconds, and every wait inside it is bounded and named
(`--timeout-seconds`, default `20`, caps the checking; tearing the adapter down
adds a separately bounded 2 s SIGTERM→SIGKILL grace outside that cap, and only
matters for an adapter that ignores SIGTERM). Run it as the precondition before paying
for anything longer — when a full session test fails, this is what separates
"the adapter never came up" from "the session logic is wrong". The verdict
vocabulary and a worked pass/fail transcript are in
[`mock-client/README.md`](../../mock-client/README.md#ice-smoke--is-a-local-adapter-reachable).

*Provenance: this subcommand and its transcript were exercised against the real
`faf-ice-adapter` 3.3.14 on Linux (WSL2, Temurin 21) on 2026-08-30 — a different
run from the Windows 11 session that produced the rest of this section. Elapsed:
1.7 s, and 1.96 s when re-run on 2026-09-01 for the refresh above. The same path
is pinned automatically by `IceSmokeLiveTest`
(`./gradlew :mock-client:integrationTest --tests '*IceSmokeLiveTest'`), which
self-skips when the adapter jar is absent.*

### The adapter subprocess alone: `launch-ice`

`ice-smoke` answers "is the adapter reachable?" and is the one to reach for
first. `launch-ice` answers a different question — "what does the adapter do
when left alone?" — and the difference is not just scope: it attaches **no
JSON-RPC peer**, which is a distinct state upstream behaves differently in
(the adapter accepts a game's connection but cannot finish serving it; see
below). It also holds the adapter up for a configurable window instead of
exiting as soon as a verdict exists, which is what makes it the right tool for
reading the adapter's own output after a version bump
([`ice-adapter-setup.md`](ice-adapter-setup.md)).

That gives the two commands a fault-localisation split worth keeping: if
`ice-smoke` reports `RPC_UNREACHABLE` but `launch-ice` shows a healthy adapter
running out its window, the fault is in the RPC layer, not the spawn. Whether
`launch-ice` should keep that boundary or start dialling JSON-RPC itself is an
open question, tracked in #279.

Build the launcher and fetch the adapter (§1), then spawn the adapter alone
with `launch-ice`:

```bash
./gradlew :mock-client:installDist
./gradlew downloadIceAdapter

./mock-client/build/install/mock-client/bin/mock-client launch-ice --duration-seconds=15 \
  --ice-adapter-binary-path="$PWD/faf-ice-adapter.jar" \
  --lobby-websocket-url=wss://ws.faforever.xyz \
  --oauth-token-url=https://hydra.faforever.xyz/oauth2/token \
  --oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth \
  --oauth-redirect-uri=http://127.0.0.1 --oauth-scopes="openid offline lobby" \
  --oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8 \
  --unique-id=00000000-0000-0000-0000-000000000000 \
  --oauth-refresh-token-file=dummy-unused-by-launch-ice
```

The OAuth flags are required by config validation but are never used by
`launch-ice` (it only spawns the adapter subprocess and never connects to its
JSON-RPC socket — there is no handshake in this path, adapter or otherwise);
any syntactically valid placeholders work, as they do above. A successful
adapter-only run looks like this in the log (`[MockClient]` = the harness,
`[ICEAdapter]` = the real jar's own output):

```text
[MockClient] Launching ICE adapter: <java> -Dlogback.configurationFile=... -jar .../faf-ice-adapter.jar --id 1 --login mock-client --game-id 0 --rpc-port 7236 --gpgnet-port 7237 --lobby-port 7238
[MockClient] ICE adapter started, pid=<pid>
[ICEAdapter] c.f.i.IceAdapter - Version: SNAPSHOT
[ICEAdapter] c.f.i.g.GPGNetServer - GPGNetServer started
[ICEAdapter] c.f.i.rpc.RPCService - Creating RPC server on port 7236
[ICEAdapter] c.n.jjsonrpc.TcpServer - TCP Server started.
[MockClient] Run window of 15s elapsed; terminating ICE adapter
[MockClient] ICE adapter terminated; exit code <code>
```

**Exit code is environment-dependent.** `IceAdapterLauncher` sends the
platform's normal termination signal at the end of the run window; on Linux
and macOS the child's exit code follows the POSIX SIGTERM convention (`143`).
On native Windows there is no POSIX signal layer, so the terminated adapter's
own exit code was observed as `1` in the run captured above — this is a
platform difference, not a failure. Either way, `mock-client` itself reports
success: no `ERROR` line, and the process exits `0` (`OK`; see the exit-code
table in [`mock-client/README.md`](../../mock-client/README.md#exit-codes)).

**What §2 stops short of.** The obvious next step — also launching the
in-repo `mock-game` against the adapter's GPGNet port with `launch-game`, to
show a full GPGNet handshake with no lobby at all — does not currently work
end to end, and this runbook will not present a workaround as if it were the
process. Since 3.2.5.1 landed, one thing blocks it, and it is **not** that the
game fails to connect. `faf-ice-adapter` 3.3.14 *accepts* the connection, then
parks the accepting thread inside `GPGNetClient`'s constructor at
`RPCService.getPeerOrWait()` — an unbounded wait for a JSON-RPC peer that
`launch-ice` alone never supplies. `GPGNetServer.currentClient` is therefore
never assigned, while that same constructor has already started the listener
thread. So the game connects successfully and is even answered; the session
dies a beat later. The full chain, with line anchors into 3.3.14, is in
[`gpgnet-format-spec.md` §8.1](../research/gpgnet-format-spec.md#section-8-1-preconditions);
`GpgNetConnectionLiveSmokeTest`'s class javadoc (WBS 3.2.2.4) carries the same
finding from the test side.

To see it for yourself, run the pair **concurrently, in two terminals**. The
adapter has to outlive the game. Build the game first — a cold Gradle
invocation can eat most of the adapter's window if you leave it until later:

```bash
./gradlew :mock-game:installDist
```

Then re-run the `launch-ice` above with `--duration-seconds=30` in the first
terminal, wait for its `GPGNetServer started` line, and in the second point
`launch-game` at the same GPGNet port:

```bash
./mock-client/build/install/mock-client/bin/mock-client launch-game --duration-seconds=20 \
  --mock-game-binary-path="$PWD/mock-game/build/install/mock-game/bin/mock-game" \
  --lobby-websocket-url=wss://ws.faforever.xyz \
  --oauth-token-url=https://hydra.faforever.xyz/oauth2/token \
  --oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth \
  --oauth-redirect-uri=http://127.0.0.1 --oauth-scopes="openid offline lobby" \
  --oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8 \
  --unique-id=00000000-0000-0000-0000-000000000000 \
  --oauth-refresh-token-file=dummy-unused-by-launch-game
```

The OAuth flags are placeholders here for the same reason as in `launch-ice`.
Both commands default to GPGNet port `7237`, so nothing needs wiring up. That
produces this — game side first, then the adapter's own output:

```text
[MockGame]   connected to GPGNet server at 127.0.0.1:7237
[MockGame]   Successful connection with GpgNet server established
[MockGame]   shutting down mock game
[MockGame]   mock game finished: status=SERVER_CONNECTION_LOST, exit code 69
[MockClient] [ERROR] mock-game exited on its own before the 20s run window; exit code 69

[ICEAdapter] c.f.i.g.GPGNetServer - Sent GPGNet message: CreateLobby 0 7238 mock-client 1 1
[ICEAdapter] Exception in thread "" java.lang.IllegalStateException: gameState must not change to null
	at ...debug.TelemetryDebugger.gameStateChanged(TelemetryDebugger.java:154)
	at ...gpgnet.GPGNetServer$GPGNetClient.processGpgnetMessage(GPGNetServer.java:131)
	at ...gpgnet.GPGNetServer$GPGNetClient.listenerThread(GPGNetServer.java:186)
```

**That transcript is the trap this section exists to close.** The connect
succeeds, the adapter replies `CreateLobby`, and only then does its listener
thread die and drop the socket — a half-completed handshake, which reads like a
codec fault and is not one. The diagnostic marker is a line that is *absent*:
`GPGNetServer - GPGNetClient has connected` never appears, because the
constructor that logs it never returned. The `ice-smoke` run above, which does
attach a peer, logs it. Three practical notes:

- The adapter's output reaches the harness pipe block-buffered, so those
  `[ICEAdapter]` lines may not surface until the run window ends and the adapter
  is terminated. Do not read their absence mid-run as absence of the fault.
- The exception is raised on a thread the adapter does not reap, so the adapter
  itself keeps running and still terminates normally with `143`.
- **The exception is the signature on a networked box, not a law.** It is
  thrown from the adapter's telemetry debugger, which deregisters itself when
  its websocket cannot connect. Where telemetry is unreachable — an
  egress-filtered CI runner — the listener thread instead blocks at
  `onGpgNetMessageReceived`, which calls `getPeerOrWait()` again: same blocker,
  no exception, no dropped socket, and the game waits rather than exiting `69`.
  §8.1 sets out both variants; the blocker is the missing peer either way.

**Two exit codes are in play here, and confusing them is easy.** The *game*
reports `SERVER_CONNECTION_LOST` and exits `69` (`ADAPTER_LOST`) — five for five
across the run quoted above and four shorter repeats, though not yet
*guaranteed*: two paths race for the state machine once the socket is gone, and
the loser reports `FAILED` and exits `70` instead (#277). #294, still open,
would make `SERVER_CONNECTION_LOST` deterministic here. #277's third outcome, a
clean `OK`, needs the match to have ended and cannot arise from a half-completed
handshake.
The `launch-game` *subcommand* still exits `70` (`RUNTIME`) regardless, because
it returns `RUNTIME` whenever the child exits before its run window. So `70`
from the subcommand means "the game stopped early", not "the game could not
reach an adapter" — for that, read the game's own status line.

`mock-game` itself is no longer an obstacle, and the reason a bare
`launch-game` — with **no adapter running at all**, the other case worth
knowing — still exits `70` has changed. 3.2.5.1 replaced the old stub `main`
with a real bootstrap, so the game now boots normally, runs its bounded
adapter-connect window (about two seconds), finds nothing listening on the
GPGNet port, and exits `70` under its own power —
`status=SERVER_NOT_CONNECTED`, which `Main` maps via `SERVER_NOT_CONNECTED,
FAILED -> ExitCodes.RUNTIME`. That is the same exit code this section reported
before 3.2.5.1, for the opposite cause: the game used to exit `0` before its
run window was ever reached, and `launch-game` supplied the `RUNTIME` itself.
Reading the `70` as "the game died early" is now wrong — it boots fine and
finds no adapter. Here the game's own code and the subcommand's agree at `70`,
which is what distinguishes this case from the adapter-with-no-peer one above.
Row 5 of [`component-isolation.md`](component-isolation.md) carries the
re-recorded output.

**The handshake is proven — by tests, not by the CLI pair.** Two live tests
cover it, at different widths, and both supply the JSON-RPC peer the pair
cannot:

- `ClientGameLifecycleLiveTest` (WBS 3.1.2.7) — the **stronger evidence, and
  the one to cite**. It drives the whole client → adapter → game path with both
  real binaries: the adapter and `mock-game` as actual subprocesses, the FSM
  advancing on real signals, the game playing out and self-exiting, teardown
  leaving nothing running.

  ```bash
  ./gradlew :mock-client:integrationTest --tests '*ClientGameLifecycleLiveTest*' --rerun
  ```

  **`--rerun` is not optional.** Without it a repeat invocation prints
  `UP-TO-DATE` and `BUILD SUCCESSFUL` in under a second, having executed
  nothing. Roughly 45 s when it does run. The captured checkpoint table is in
  [`demos/README.md`](../demos/README.md) under *client-game-lifecycle*.

- `GpgNetConnectionLiveSmokeTest` (WBS 3.2.2.4) — the narrower one, useful when
  you need the GPGNet seam **alone**:
  `./gradlew :mock-game:integrationTest --tests '*GpgNetConnectionLiveSmokeTest'`.
  It drives the protocol in-process, holds a plain TCP socket on the RPC port —
  all `getPeerOrWait()` needs to be released — and pauses before its first
  frame, which is §8.1's *second* precondition.

Both self-skip (they do not fail) when either required binary is absent — the
adapter jar, or `mock-game` for the lifecycle test — so a skip is the expected
result without §1's setup, not a failure. See
[`component-isolation.md`](component-isolation.md) for the wider `test` /
`integrationTest` split the live rows follow. A skip is not a pass: check for
the skip line before believing a green run.

**On #236's acceptance criterion** — "someone with no FAF account can follow
this section from a clean clone to a completed GPGNet handshake against a real
adapter". That is **met**, and by a shorter path than this section once
implied: `ClientGameLifecycleLiveTest` needs no account and no credentials, and
requires no network of its own, so `./gradlew downloadIceAdapter` followed by
the command above takes a clean clone to a completed handshake against the real
adapter. ("Requires" is the operative word: the adapter subprocess still opens a
telemetry websocket to `ice-telemetry.faforever.com`, which 3.3.14 offers no way
to disable. It is non-blocking — off-network it logs an error and the run
proceeds — so treat that noise as expected, not as a broken harness. See
[`ice-adapter-setup.md`](ice-adapter-setup.md).)

What is *not* met is the same thing by way of the **CLI pair** —
`launch-ice` alongside `launch-game` — which is what the rest of this section
is about and what #279 would fix. `ice-smoke` does not close that gap either:
it connects a JSON-RPC client, but only for the couple of seconds its own check
runs and it terminates the adapter on the way out, so it cannot hold a peer
open for a separately launched game. It also deliberately sends no GPGNet
frame, so it proves *reachability*, never a handshake. A `launch-ice`-only run,
as captured above, remains the right and sufficient adapter-alone check for a
CI embedding this release today.

## 3. Credentials

Needed only for the live-lobby path (§1's second list). Skip this section
entirely if you only need §2.

1. **A FAF test account.** Test users on the `*.faforever.xyz` environment
   share the password `foo` (`documentation/research/lobby-protocol-spec.md`
   §2). No signup step — log in with any known test username against Hydra
   in the bootstrap below.
2. **Bootstrap a refresh token** (manual, one-time, valid ~30 days):
   - Visit, in a browser:
     `https://hydra.faforever.xyz/oauth2/auth?client_id=95ecec08-29c1-4c48-ae0a-b000ff349cb8&response_type=code&redirect_uri=http://127.0.0.1&scope=openid+offline+lobby&state=<random, ≥8 chars>`
   - Log in as the test user and grant consent.
   - The browser redirects to a `127.0.0.1` URL that fails to load (nothing
     listens there) — copy the `code=` query parameter from that URL.
   - Exchange it at `https://hydra.faforever.xyz/oauth2/token` with
     `grant_type=authorization_code` for a JSON response containing
     `refresh_token`.
   - Write the `refresh_token` value, and nothing else, to
     `.secrets/refresh_token.txt` (gitignored). This exact path is what every
     live test and demo in this repo reads; see §4 for why the config example
     must point at it too.
   - Full step-by-step reference: `documentation/research/lobby-protocol-spec.md`
     §2 (this is the spec these steps are transcribed from; it is not
     restated further here).
3. **Obtain `faf-uid`.** Download the release binary for your platform from
   [FAForever/uid releases](https://github.com/FAForever/uid/releases) and
   make it executable (`chmod +x faf-uid` on Linux/macOS). It embeds the
   public key the lobby's policy server expects; a placeholder UID is
   rejected outright.

**Not run from this network/session.** The browser step above needs an
interactive user with FAF credentials and could not be executed headlessly
here — it is transcribed from `lobby-protocol-spec.md` §2, whose procedure
was verified end-to-end on 2026-05-05, and the resulting full session was
independently captured live on 2026-07-14
([`demos/lobby-connect-idle.log`](../demos/lobby-connect-idle.log)). Reachability
of the lobby host itself **was** verified from this session's network
(2026-08-21): `wss://ws.faforever.xyz:443` accepts a TCP connection;
`wss://lobby.faforever.xyz:443` times out (see §8 for why that second
hostname appears at all).

## 4. Configuration

Every Mock Client field is resolved from four layered sources, lowest to
highest priority: built-in defaults → JSON config file (`--config`) → `FAF_MOCK_CLIENT_*`
environment variables → CLI flags. The full field reference, defaults, and
env-var/flag names are `--help`'s output and the table in
[`mock-client/README.md`](../../mock-client/README.md#field-reference) — not
restated here.

The minimum set of values for one live session:

```bash
cp mock-client/mock-client.example.json mock-client.json
```

then edit `mock-client.json` so it has:

| Key | Value for this session |
|---|---|
| `lobbyWebSocketUrl` | `wss://ws.faforever.xyz` — the verified FAF test-env lobby endpoint. This is the **one, unambiguous** value; see §8 for the endpoints this corrects. |
| `oauthTokenUrl` | `https://hydra.faforever.xyz/oauth2/token` |
| `oauthAuthEndpoint` | `https://hydra.faforever.xyz/oauth2/auth` |
| `oauthRedirectUri` | `http://127.0.0.1` |
| `oauthScopes` | `openid offline lobby` |
| `oauthClientId` | `95ecec08-29c1-4c48-ae0a-b000ff349cb8` (seeded `FAF Classic Client (Python)`) |
| `oauthRefreshTokenFile` | `./.secrets/refresh_token.txt` — the file §3 wrote |
| `uidBinaryPath` | `./faf-uid` (or wherever §3's binary landed) |

`mock-client.example.json`, tracked in version control, already carries the
first six as its committed defaults — you only need to add
`oauthRefreshTokenFile` and `uidBinaryPath`, both of which point at
machine-local, gitignored paths. **Never commit a real refresh token or
access token** — see the Secrets section of
[`mock-client/README.md`](../../mock-client/README.md#secrets) for the
CI-shaped alternative (public values in the tracked file, the secret injected
as an env var).

## 5. Running one client session

```bash
./gradlew :mock-client:installDist
./mock-client/build/install/mock-client/bin/mock-client run --config mock-client.json
```

(Skip the build step if you already ran it for §2.) Use the installed
launcher, not `./gradlew :mock-client:run`: Gradle's `run` task executes from
`mock-client/`, not the repo root, so `--config mock-client.json` (written to
the repo root in §4) fails with `config file is not readable: mock-client.json`
before the process ever tries to connect. Gradle also collapses every exit
code below to its own `1`, so the picocli/`RUNTIME` distinction the next
section depends on is lost. The installed binary, run from the repo root, has
neither problem.

A successful run prints, in order, the lines documented in
[`mock-client/README.md`](../../mock-client/README.md#harness-log-contract)
and demonstrated live in
[`demos/lobby-connect-idle.log`](../demos/lobby-connect-idle.log):

```text
lobby WebSocket connected: wss://ws.faforever.xyz
generated unique_id via faf-uid (<n> chars)
lobby authenticated as login=<name>
session ready: id=<id> login=<name>
mock client idle as player id=<id> login=<name>; press Ctrl-C to exit
```

The session then sits idle, auto-answering the lobby's `ping` heartbeat with
`pong`, until you end it.

### Ending the session

| How | Exit code | Notes |
|---|---|---|
| `Ctrl-C` / `SIGTERM` | 130 (SIGINT) / 143 (SIGTERM) on Linux/macOS | Clean: closes the WebSocket via a JVM shutdown hook, logs `shutdown signal received; tearing down session`, no error lines. On native Windows, POSIX signal numbers don't apply — expect a platform-specific code rather than literally 130/143 (see the same caveat in §2). These are the launcher's own codes, observable directly only when it is invoked as in §5 — running it through `./gradlew :mock-client:run` instead reports Gradle's own `1` for every non-zero case regardless of the underlying code. |
| Config error (missing required option, unreadable file, bad URI/port) | `2` (`USAGE`) | Picocli prints the usage block plus the missing-option list before any connection is attempted — see the Failure mode section of `mock-client/README.md`. |
| Runtime failure after start (bad refresh-token file, lobby session failure) | `70` (`RUNTIME`) | |

## 6. Reading the output

Console output mirrors what lands in the JSONL log file, one record per line,
each carrying a millisecond `timestamp`, a `component`, and (for multi-peer
runs) an `instance`. Default path is `logs/<component>.jsonl`
(`logs/mockclient.jsonl` for `run`); override with `--log-file` /
`FAF_MOCK_CLIENT_LOG_FILE` / `logFile`.

To follow a session: tail the file (`tail -f logs/mockclient.jsonl` or
equivalent) and watch for the lifecycle, identity, and connection-state lines.
**The field and line contract — which lines exist, their exact shape, and
which are pinned by tests — is authoritative in
[`mock-client/README.md`](../../mock-client/README.md#harness-log-contract)
and is not repeated here.**

## 7. When it does not work

| Symptom | Log line to look for | Cause / fix |
|---|---|---|
| Login ends without a `session ready` line; last relevant frame is a rejected auth | `{"command":"invalid"}` | The lobby's policy server rejected a placeholder `unique_id` — `uidBinaryPath` is unset or wrong. Set it to a real `faf-uid` binary (§1, §3). There is no way to reach a live session without this. |
| `run` fails immediately after the token exchange | `invalid_grant` or `invalid_client` from Hydra | The refresh token was rotated by a previous run and this file is now stale, or it was minted against a retired client ID. Full re-bootstrap: repeat §3 step 2 from a browser: a rotated-but-unpersisted token, or a crash between rotation and persistence, both look like this. There is no partial recovery — get a fresh `code=` and refresh token. |
| `run` hangs on connect, then times out with no `lobby WebSocket connected` line | (none — silence is the symptom) | `wss://ws.faforever.xyz` is Cloudflare-fronted and publicly reachable (§3, §8 verified this directly) — no FAF allowlist or VPN is needed for it. Look locally first: DNS resolution, an intercepting proxy, or an outbound firewall rule on this machine/network. Confirm with a raw TCP probe to `ws.faforever.xyz:443` before assuming a code problem. |
| Any of the above, but you're not sure which component is at fault | — | Narrow it with [`component-isolation.md`](component-isolation.md) — the fault-localisation walk from full-stack failure down to one seam or one subprocess, with the exact command and expected result for each. |

## 8. Contradictions in prior documentation, resolved here

Three values disagreed across the source documents this runbook was written
from. All three are now fixed at the source, in this same change, not just
avoided here:

1. **The lobby endpoint.** `mock-client.example.json` and `demos/README.md`
   used `wss://ws.faforever.xyz`; `LobbyConnectionLiveSmokeTest`'s javadoc and
   its `FAF_TEST_LOBBY` constant instead claimed `wss://lobby.faforever.xyz`
   was canonical, sourced from `downlords-faf-client`'s `application-test.yml`.
   Verified from this session's network (2026-08-21, `Test-NetConnection`):
   `ws.faforever.xyz:443` accepts a connection, `lobby.faforever.xyz:443`
   times out — matching the empirical correction already recorded in
   `documentation/research/lobby-protocol-spec.md` §1 (2026-06-18) but not
   yet propagated into the live smoke test. `LobbyConnectionLiveSmokeTest.java`
   is corrected in this change to target `wss://ws.faforever.xyz`, with its
   javadoc rewritten to point at the spec's correction instead of restating
   the now-wrong sourcing. **`wss://ws.faforever.com` is not a contradiction**
   — it is the correctly-labelled production counterpart, referenced
   alongside the test endpoint in both `lobby-protocol-spec.md` and
   `demos/README.md`.
2. **The refresh-token file path.** `mock-client.example.json` and
   `mock-client/README.md`'s worked examples used
   `.secrets/refresh-token` (no extension); every live test, the auth
   bootstrap procedure, and `demos/README.md` use
   `.secrets/refresh_token.txt`. The latter is what the code and every other
   document actually reads and writes; the example config and README are
   corrected in this change to match.
3. **This document's relationship to `demos/README.md`.** This runbook
   supersedes `demos/README.md`'s "Prerequisites" and "Configuration"
   framing as the ordered setup path — a reader should start here, not there.
   `demos/README.md` remains the sprint-review evidence record for each
   individual demo — its captured transcript, its acceptance-criteria mapping,
   and how to capture a fresh recording. It covers `lobby-connect-idle` (whose
   captured log §6 links directly), `client-game-lifecycle` (linked from §2),
   and `two-peer-session`, whose subject is out of scope here (see §9). Both
   files now say this explicitly, so a reader is never following two versions
   of the same setup path.

## 9. Multi-peer sessions (R79b)

Running two or more Mock Client instances on one box — per-instance ports,
log attribution, the `INSTANCE_NAME` convention, and true N-peer sessions —
is out of scope for this document. It lands with R79b, immediately after the
two-peer and N-peer cards, as sections appended here rather than a
restructure of what exists above.
