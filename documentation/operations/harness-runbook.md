# Harness Runbook: Setup and Single Session (WBS 7.2.1)

Everything a newcomer needs to run the Mock Client, in one ordered path. Three
audiences, in this order:

1. Someone embedding the mock game in another project's tests. **No FAF
   account, credentials, or network beyond localhost.** Start at
   [§2](#2-running-the-game-against-an-adapter-no-lobby-required).
2. Someone running a full client session against the live test lobby. Needs a
   FAF test account. Continue to [§3](#3-credentials) onward.
3. Someone wiring a CI job that runs a session unattended against their own
   adapter build. Read [§2a](#2a-the-jar-only-path-no-clone) and
   [§3](#3-credentials) first for the jars and the credentials, then
   [§11](#11-a-session-in-a-consumers-ci-wbs-421).

This document sequences and resolves material that already exists in
[`documentation/demos/README.md`](../demos/README.md),
[`mock-client/README.md`](../../mock-client/README.md),
[`ice-adapter-setup.md`](ice-adapter-setup.md), and
[`component-isolation.md`](component-isolation.md) — it is not a rewrite of
any of them, and it does not restate their field reference, log-line contract,
or developer conventions. Two Mock Clients on one box (WBS-4.3.1) are covered
at [§9](#9-two-peer-sessions-wbs-431); three and four peers already run the
same way (WBS-4.3.3) but this document's write-up of that shape is deferred —
see the note at the end of that section. Running a session unattended in CI is
[§11](#11-a-session-in-a-consumers-ci-wbs-421).

## 1. Prerequisites

### Always needed (every audience)

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
- **A credential for it** (§3), either an **OAuth refresh token**, bootstrapped
  once through a browser, or a **pre-signed access token** handed to you and used
  as-is, which needs no bootstrap and no account password.
- **The `faf-uid` binary**, referenced by the `uidBinaryPath` config key. This
  is a **hard requirement** for a live session: the lobby's policy server
  rejects a placeholder `unique_id`, and the login ends in
  `{"command":"invalid"}` without it. There is no way around it for a real
  session — see §3 for how to obtain it.

## 2. Running the game against an adapter, no lobby required

This is the no-account path: no account, no OAuth, and — once §1's one-time
`downloadIceAdapter` has fetched the jar — no network beyond localhost, save
the adapter's own telemetry websocket, which 3.3.14 cannot be told to skip and
which fails harmlessly when it cannot connect (see the note on #236 below).

**Two ways in.** [§2a](#2a-the-jar-only-path-no-clone) is the jar-only path: pull
the published jars from the latest release and run them, which is what embedding
this in another project's CI looks like. Everything after it assumes the
repository and drives the harness through `./gradlew`. The two produce the same
runs; the clone buys you the adapter-provisioning task, the live tests, and the
ability to point the harness at an adapter you built yourself.

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

### 2a. The jar-only path (no clone)

Everything here runs from the two published jars. No Gradle, no checkout.

**Fetch the latest release.** This is the route to use; it needs no
authentication and no pinned version:

```bash
curl -s https://api.github.com/repos/md-173/faf-test-harness/releases/latest \
  | grep -o '"browser_download_url": *"[^"]*-all\.jar"' \
  | cut -d'"' -f4 \
  | xargs -n1 curl -sfLO
```

That leaves `mock-client-<version>-all.jar` and `mock-game-<version>-all.jar` in
the working directory. Releases cut after the checksum step landed also carry a
`.sha256` beside each jar; fetch it the same way and `sha256sum -c
mock-game-<version>-all.jar.sha256` to check the download. For any release, the
API also exposes a per-asset digest, which needs nothing beside the jar:

```bash
curl -s https://api.github.com/repos/md-173/faf-test-harness/releases/latest \
  | grep -o '"digest": *"sha256:[^"]*"'
sha256sum mock-client-<version>-all.jar
```

Both assets are named on that pattern deliberately — a consumer matching on it is
relying on a contract, and it is intended as one. `./gradlew check` asserts both
names (`verifyReleaseAssetName`, defined in the root `build.gradle`), so a rename
fails the pull request that makes it rather than the next release.

To record which build a pipeline ran, ask the jar rather than the file name:
`java -jar mock-client-<version>-all.jar --version` prints `mock-client
<version>`, read from the jar manifest, and `mock-game` prints its own the same
way. The release workflow fails if either disagrees with the version it was
given. Releases before this landed report the build default instead: `0.2.0`
reports `mock-client 1.0-SNAPSHOT`, and the 0.1.0 and 0.2.0 `mock-game` jars have
no `--version` at all.

You also need `faf-ice-adapter` itself, which is not ours to publish. Take the
pinned version from
[FAForever/java-ice-adapter](https://github.com/FAForever/java-ice-adapter/releases)
— the `-nojfx` build, headless — or point the harness at one you built. This is a
plain shell variable for the snippets below, not a setting the harness reads; the
CLI's own environment variable for the same thing is
`FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH`, and `FAF_ICE_ADAPTER_JAR` means
something else again (the integration-test override, which needs a clone).

```bash
ADAPTER_JAR=/path/to/faf-ice-adapter-3.3.14-nojfx.jar
```

**Shape 1 — is the harness working?** `ice-smoke` spawns the adapter, checks
both of its endpoints and tears it down, with no credentials of any kind:

```bash
java -jar mock-client-<version>-all.jar ice-smoke \
  --ice-adapter-binary-path="$ADAPTER_JAR" \
  --timeout-seconds=20
```

Exit `0` means reachable. Every negative verdict is exit `70` — the phase is named
in the log line, not in the exit status, so a consumer cannot branch on it; the
verdict table is in
[`mock-client/README.md`](../../mock-client/README.md#ice-smoke--is-a-local-adapter-reachable).

**Shape 2 — mock-game against an adapter you started.** If your pipeline already
runs an adapter of its own, drive the game straight at it and skip mock-client
entirely:

```bash
java -jar mock-game-<version>-all.jar \
  --gpgnet-port 7237 --lobby-port 7238 \
  --player-id 42 --player-login ci-runner --game-uid 0
```

These two ports are not free to choose: the game rejects a lobby port that does
not match the adapter's, so use whatever the adapter you started was given. Its
exit codes are `0` (played a match through), `69` (the adapter went away
mid-session), `70` (never reached the adapter, or some other runtime failure) and
`2` (bad invocation); the root [`README.md`](../../README.md) carries them in
context, and `component-isolation.md` row 5 records a real standalone run.

Note that a game nothing drives into a role waits in the lobby indefinitely, by
design — so in CI it is your step timeout that ends the run, and the exit code is
the signal's (`143`), not one of the harness's. Wrap it in `timeout 30 java -jar
…` if you would rather bound it yourself, noting that GNU `timeout` reports `124`
rather than `143` unless you pass `--preserve-status`.

**Ports.** Three overrides move everything the adapter binds, for a runner where
`7236`–`7238` are taken. Note the hyphenation of the second one:

| Flag | Default | What it moves |
| :--- | :--- | :--- |
| `--ice-adapter-rpc-port` | `7236` | The adapter's JSON-RPC port. |
| `--ice-adapter-gpg-net-port` | `7237` | The GPGNet TCP port the game connects to. |
| `--ice-adapter-lobby-port` | `7238` | The UDP port used for game traffic. |

`ice-smoke` pre-flights both TCP ports before launching anything and reports
`PORTS_IN_USE` naming the port, which is the common case: another adapter left
running by an earlier step.

**On Linux, including GitHub's hosted runners, that pre-flight holds** and a busy
port does report `PORTS_IN_USE`. On macOS the bind test can pass alongside an
existing wildcard listener (measured against `0.0.0.0:7236`) — permitting a
duplicate listening bind is BSD and Darwin behaviour, where `SO_REUSEADDR` is
enough; on Linux that needs `SO_REUSEPORT`, which `ServerSocket` never sets. When
it does slip through, the run fails later instead: usually `ADAPTER_EXITED`,
because the adapter dies on its own failed bind and the liveness re-check catches
it, or `RPC_SILENT` in the one phase that has no such check. Treat
`ADAPTER_EXITED`, `RPC_SILENT` or `RPC_UNREACHABLE` on a runner as "check the
ports too", not only as "the adapter is broken".

**Exit `70` is ambiguous, and there is no way around it from the code alone.** It
covers both "the binary is missing or would not start" and "the ports were busy",
because both are runtime failures of the same command. A consumer branching on
exit codes cannot tell them apart — read the log line, which names which it was.
`ice-smoke` is the reason to prefer it over `launch-ice` in a pipeline: its verdict
is in the output, so a missing binary, a busy port and a silent adapter are three
different lines rather than three identical `70`s.

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

Since WBS-3.1.6.3 (#279) `launch-ice` also **attaches a JSON-RPC peer** and holds
it open for the window. That is what makes the pair below work: the adapter
will not serve a game until an RPC client exists, so a `launch-ice` without one
was an adapter no game could use. The two commands still split usefully — one
gives a verdict and exits, the other holds a live adapter up for something else
to talk to.

Build the launcher and fetch the adapter (§1), then spawn the adapter alone
with `launch-ice`:

```bash
./gradlew :mock-client:installDist
./gradlew downloadIceAdapter

./mock-client/build/install/mock-client/bin/mock-client launch-ice --duration-seconds=15 \
  --ice-adapter-binary-path="$PWD/faf-ice-adapter.jar"
```

That is the whole invocation. `launch-ice` opens no lobby connection, so since
WBS-3.1.5.2-fix (#308) it validates only the adapter settings and needs no
credentials — the eight placeholder OAuth flags this section used to print,
`--oauth-refresh-token-file=dummy-unused-by-launch-ice` among them, are gone.
A successful adapter-only run looks like this in the log (`[MockClient]` = the
harness, `[ICEAdapter]` = the real jar's own output):

```text
[MockClient] Launching ICE adapter: <java> -Dlogback.configurationFile=... -jar .../faf-ice-adapter.jar --id 1 --login mock-client --game-id 0 --rpc-port 7236 --gpgnet-port 7237 --lobby-port 7238
[MockClient] ICE adapter started, pid=<pid>
[ICEAdapter] c.f.i.IceAdapter - Version: SNAPSHOT
[ICEAdapter] c.f.i.g.GPGNetServer - GPGNetServer started
[ICEAdapter] c.f.i.rpc.RPCService - Creating RPC server on port 7236
[ICEAdapter] c.n.jjsonrpc.TcpServer - TCP Server started.
[MockClient] connected to ICE adapter JSON-RPC at 127.0.0.1:7236
[ICEAdapter] c.n.j.SocketListener - New client connected on port <ephemeral>
[MockClient] JSON-RPC peer attached on port 7236; the adapter can now serve a game
[MockClient] Run window of 15s elapsed; terminating ICE adapter
[MockClient] ICE adapter terminated; exit code <code>
```

`JSON-RPC peer attached` is the line that says this adapter can serve a game. If
it is missing, the run failed (`70`, `RUNTIME`) rather than leaving you an
adapter that looks healthy and drops the first game that connects to it.

**Exit code is environment-dependent.** `IceAdapterLauncher` sends the
platform's normal termination signal at the end of the run window; on Linux
and macOS the child's exit code follows the POSIX SIGTERM convention (`143`).
On native Windows there is no POSIX signal layer, so the terminated adapter's
own exit code was observed as `1` in the run captured above — this is a
platform difference, not a failure. Either way, `mock-client` itself reports
success: no `ERROR` line, and the process exits `0` (`OK`; see the exit-code
table in [`mock-client/README.md`](../../mock-client/README.md#exit-codes)).

**The pair completes a GPGNet handshake with no lobby at all.** Launching the
in-repo `mock-game` against the adapter's GPGNet port with `launch-game` is the
next step, and since WBS-3.1.6.3 (#279) it works end to end.

What used to block it was the missing RPC peer, and it is worth knowing because
it is the shape of every "the game connects and then the session dies" report.
`faf-ice-adapter` 3.3.14 *accepts* the game's connection, then parks the
accepting thread inside `GPGNetClient`'s constructor at
`RPCService.getPeerOrWait()` — an unbounded wait for a JSON-RPC peer. With no
peer, `GPGNetServer.currentClient` is never assigned while that same constructor
has already started the listener thread, so the game connects, is even answered,
and the session dies a beat later with `IllegalStateException: gameState must
not change to null`. `launch-ice` now supplies the peer, so none of that happens.
The full chain, with line anchors into 3.3.14, is in
[`gpgnet-format-spec.md` §8.1](../research/gpgnet-format-spec.md#section-8-1-preconditions);
`GpgNetConnectionLiveSmokeTest`'s class javadoc (WBS 3.2.2.4) carries the same
finding from the test side.

Run the pair **concurrently, in two terminals**. The adapter has to outlive the
game — the game's exit is otherwise the adapter's termination reaching it, which
reports as `SERVER_CONNECTION_LOST` and looks like a fault. Build the game first;
a cold Gradle invocation can eat most of the adapter's window if you leave it
until later:

```bash
./gradlew :mock-game:installDist
```

Then re-run the `launch-ice` above with `--duration-seconds=30` in the first
terminal, wait for its `GPGNetServer started` line, and in the second point
`launch-game` at the same GPGNet port:

```bash
./mock-client/build/install/mock-client/bin/mock-client launch-game --duration-seconds=12 \
  --mock-game-binary-path="$PWD/mock-game/build/install/mock-game/bin/mock-game"
```

No credentials here either, for the same reason as `launch-ice` (#308). Both
commands default to GPGNet port `7237`, so nothing needs wiring up. That
produces this — game side first, then the adapter's own output (captured
against the real 3.3.14 jar):

```text
[MockClient] Launching mock-game: .../mock-game --gpgnet-port 7237 --lobby-port 7238 --player-id 1 --player-login mock-client --game-uid 0 --launch-delay-seconds 5
[MockGame]   mock game started: playerId=1 login=mock-client gameUid=0 gpgNetPort=7237 lobbyPort=7238 gameOptions={} launch=auto after 5s
[MockGame]   connected to GPGNet server at 127.0.0.1:7237
[MockGame]   Successful connection with GpgNet server established

[ICEAdapter] c.f.i.g.GPGNetServer - GPGNetClient has connected
[ICEAdapter] c.f.i.g.GPGNetServer - Sent GPGNet message: CreateLobby 0 7238 mock-client 1 1
[ICEAdapter] c.f.i.g.GPGNetServer - Received GPGNet message: GameState Idle
[ICEAdapter] c.n.j.JJsonPeer - Sending Notification:{"method":"onGpgNetMessageReceived","params":["GameState",["Idle"]],"jsonrpc":"2.0"}
[ICEAdapter] c.f.i.g.GPGNetServer - Received GPGNet message: GameState Lobby
```

**That is the handshake, and the lines to read are the ones that used to be
missing.** `GPGNetClient has connected` is logged by the constructor that used
to park forever, so its presence is the proof that a peer was attached. The
`IllegalStateException: gameState must not change to null` that this section
used to document does not occur. `GameState Idle` → `CreateLobby` → `GameState
Lobby` is the full exchange.

Two practical notes:

- The adapter's output reaches the harness pipe block-buffered, so those
  `[ICEAdapter]` lines may not surface until the run window ends and the adapter
  is terminated. Do not read their absence mid-run as absence of anything.
- **Give the adapter the longer window.** If `launch-ice` ends first, its
  termination reaches the game as a lost connection and the game exits `69`
  (`SERVER_CONNECTION_LOST`) — a correct report of what happened to it, and easy
  to misread as a handshake failure. `--duration-seconds=30` on the adapter
  against `12` on the game leaves plenty of room.

**Two exit codes are in play once the adapter goes away, and confusing them is
easy.** When the adapter's window ends first, the *game* reports
`SERVER_CONNECTION_LOST` and exits `69` (`ADAPTER_LOST`) — though not yet
*guaranteed*: two paths race for the state machine once the socket is gone, and
the loser reports `FAILED` and exits `70` instead (#277). #294, still open,
would make `SERVER_CONNECTION_LOST` deterministic here.
The `launch-game` *subcommand* exits `70` (`RUNTIME`) regardless, because it
returns `RUNTIME` whenever the child exits before its run window. So `70` from
the subcommand means "the game stopped early", not "the game could not reach an
adapter" — for that, read the game's own status line.

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
which is what distinguishes this case from an adapter that went away mid-session.
Row 5 of [`component-isolation.md`](component-isolation.md) carries the
re-recorded output.

**The handshake is also covered by tests, at two widths.** The CLI pair above
shows it by hand; these assert it, and both drive more of the path than the pair
does:

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

Since WBS-3.1.6.3 (#279) it is also met by way of the **CLI pair** —
`launch-ice` alongside `launch-game`, captured above — because `launch-ice` now
holds a JSON-RPC peer open for the whole run window. `ice-smoke` still does not
close that gap and is not meant to: it attaches a peer only for the couple of
seconds its own check runs and terminates the adapter on the way out, so it
cannot hold one open for a separately launched game, and it deliberately sends
no GPGNet frame — it proves *reachability*, never a handshake. Three commands,
three questions: `ice-smoke` for a verdict, `launch-ice` for an adapter to talk
to, the pair for a handshake by hand.

## 3. Credentials

Needed only for the live-lobby path (§1's second list). Skip this section
entirely if you only need §2.

1. **A FAF test account.** Test users on the `*.faforever.xyz` environment
   share the password `foo` (`documentation/research/lobby-protocol-spec.md`
   §2). No signup step — log in with any known test username against Hydra
   in the bootstrap below.
2. **Bootstrap a refresh token** (manual, one-time, valid ~30 days). Skip this
   step if someone has handed you a pre-signed access token instead; that
   channel is [below](#the-other-credential-channel-a-pre-signed-access-token).
   Otherwise:
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

### The other credential channel: a pre-signed access token

`--oauth-access-token-file` takes a token someone else signed and sends it as-is
— no exchange with Hydra, no rotation, no file rewriting (WBS-3.1.6.4). It is
mutually exclusive with `--oauth-refresh-token-file`. Where both are configured
at different layers, the higher layer wins: CLI flag, then `FAF_MOCK_CLIENT_*`,
then the config file. Both at the same layer is a config error naming them.

```bash
printf '%s' "$FAF_ACCESS_TOKEN" > .secrets/access_token.jwt
mock-client run --oauth-access-token-file=.secrets/access_token.jwt ...
```

**Why it is worth having.** The refresh-token path above is a manual browser
bootstrap producing a rotating secret that only the developer who created it
holds. That is the difference between a live test one person can run and one CI
can: a statically signed token has no rotation to lose and no bootstrap to
repeat. A file rather than a flag value so the token stays out of the process
table and out of build logs.

**What it cannot do is renew.** Neither channel renews mid-run, and nothing in
the harness reads a token's expiry. The refresh channel exchanges once, when a
run starts, so every run begins on a token minted seconds earlier. A pre-signed
token is whatever was minted whenever it was minted, so an expired one is still
*sent*, and the lobby rejects it. That is deliberate: the lobby's rejection
identifies a bad token far more precisely than a local expiry guess could. Expect
the lobby's own auth failure, not a harness error, and re-mint the token.

`--oauth-token-url` and `--oauth-client-id` are not required on this channel,
since nothing is exchanged. `--oauth-auth-endpoint`, `--oauth-redirect-uri` and
`--oauth-scopes` are required by neither channel: they describe the one-time
browser bootstrap earlier in this section, which no run performs.

**What the lobby checks.** `oauth_service.get_player_id_from_token` accepts a
token only if all four of these hold. There is no fifth check hiding anywhere,
and no local check at all: the first thing that inspects the token is the lobby.

- **RS256, signed with a key the lobby's JWKS publishes**, located by the token's
  `kid` header. For `ws.faforever.xyz` that JWKS is
  `https://hydra.faforever.xyz/.well-known/jwks.json`. The token does not have to
  come from Hydra's own OAuth flow, but the key that signed it has to be one that
  endpoint publishes, and only Hydra holds those private keys. Signing your own
  therefore means pointing the harness at a lobby whose JWKS you control.
- **`exp` in the future, if present.** PyJWT checks `exp`, `nbf` and `iat` by
  default when the claims are there, and none of them is required to be there. A
  token with no `exp` is accepted.
- **`lobby` in `scp`.**
- **A numeric `sub` naming an account on that environment.** That claim is the
  player id the session runs as.

Audience and issuer are **not** checked. The lobby decodes with
`verify_aud: False` and passes no issuer, so `aud` and `iss` are ignored even
though Hydra sets both. Do not spend time matching them.

`faf-uid` is still required on this channel. The `auth` frame carries
`unique_id` whichever channel produced the token, and the lobby posts it to its
policy server for token logins exactly as for password logins. The verdict is
currently ignored, but the request is not: a placeholder the policy server
refuses makes that request fail, and the login then ends in
`{"command":"invalid"}` rather than an auth error. Keep `--uid-binary-path`
pointed at a real `faf-uid`.

**What a rejection looks like.** Most faults collapse into one message, because
the lobby catches `InvalidTokenError`, `KeyError` and `ValueError` together. The
harness logs the lobby's text verbatim:

| `lobby authentication_failed:` | What to look at |
|---|---|
| `Token signature was invalid` | `kid` missing or absent from the JWKS; a signature that key does not verify; an expired `exp`; `scp` missing entirely; `sub` missing or not a number |
| `Token does not have permission to login to the lobby server` | `scp` is present but does not contain `lobby` |
| `Cannot find user id` | `sub` is a number but names no account on this environment |

A bad token can also produce no `authentication_failed` line at all, just
`{"command":"invalid"}` and a closed connection. That means either the lobby
could not fetch its own JWKS, or a claim had an unexpected JSON type, such as
`scp` as a number rather than a list. Both escape the catch above. It is also
what a placeholder `unique_id` produces, so rule the UID out before suspecting
the token.

**Reading a token without printing it.** This prints the five claims that decide
the outcome and nothing else. It never prints the token, and never touches the
signature:

```bash
python3 - .secrets/access_token.jwt <<'EOF'
import base64, json, string, sys, time

WANTED = ("alg", "kid", "sub", "scp", "exp")
B64URL = set(string.ascii_letters + string.digits + "-_")
try:
    try:
        raw = open(sys.argv[1], encoding="utf-8").read().strip()
    except UnicodeDecodeError:
        raise ValueError("it is not UTF-8 text")
    if not raw or any(c.isspace() for c in raw):
        raise ValueError("it holds whitespace, so more than just the token")
    parts = raw.split(".")
    if len(parts) != 3:
        raise ValueError("it has %d dot-separated segments, not 3" % len(parts))
    claims = {}
    for n, part in enumerate(parts[:2], 1):
        if not part or set(part) - B64URL or len(part) % 4 == 1:
            raise ValueError("segment %d is not base64url" % n)
        obj = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
        if not isinstance(obj, dict):
            raise ValueError("segment %d is not a JSON object" % n)
        claims.update({k: v for k, v in obj.items() if k in WANTED})
except OSError as exc:
    sys.exit("cannot open that file: %s" % exc.strerror)
except ValueError as exc:
    sys.exit("that file is not a JWT access token: %s" % exc)

for name in WANTED:
    print("%-4s %s" % (name + ":", claims.get(name, "(absent)")))
exp = claims.get("exp")
if isinstance(exp, int) and not isinstance(exp, bool):
    try:
        when = time.strftime("%Y-%m-%d %H:%M:%SZ", time.gmtime(exp))
    except (OverflowError, OSError, ValueError):
        when = "a time out of range"
    print("     expires %s (%s)" % (when, "EXPIRED" if exp <= time.time() else "valid now"))
elif exp is not None:
    print("     exp is not a number, so the lobby will reject this token")
EOF
```

Output is five lines plus an expiry verdict, with `(absent)` naming any claim
that is not there, which is what tells a `Token signature was invalid` apart from
the several other things that produce it. The base64url padding the JWT strips is
restored before decoding, so no segment length fails.

Two things not to tidy. The `<<'EOF'` quotes are load-bearing: unquoted, the
shell expands `$` inside the script. And keep the token path quoted if it can
contain spaces.

This does not tell you whether the signature is good, only what the token claims.
A token that reads correctly here and is still rejected has a signature the
lobby's JWKS does not verify, or a `sub` that names no account there.

**A run with no other OAuth options.** Nothing below configures OAuth except the
token file:

```bash
./gradlew :mock-client:installDist
./mock-client/build/install/mock-client/bin/mock-client run \
  --oauth-access-token-file=.secrets/access_token.jwt \
  --lobby-websocket-url=wss://ws.faforever.xyz \
  --unique-id=placeholder \
  --uid-binary-path=./faf-uid \
  --ice-adapter-binary-path=./faf-ice-adapter.jar \
  --mock-game-binary-path=mock-game/build/libs/mock-game-<version>-all.jar \
  --host-title="token channel check" \
  --host-map=scmp_007 \
  --host-mod=faf \
  --host-visibility=friends \
  --mock-game-launch-delay-seconds=-1
```

Four of those are less obvious than they look. `--unique-id` satisfies the
required field and `--uid-binary-path` then overrides it at handshake time with
real `faf-uid` output, so both are needed. The four `--host-*` options have to be
set together or not at all; a partial set is rejected by name, and omitting all
four leaves the session at IDLE rather than HOSTING.
`--mock-game-launch-delay-seconds=-1` is what makes HOSTING an observable state:
the default of 5 has mock-game start the match on its own, moving the client
straight on to PLAYING.

Run live on 2026-09-16 against `ws.faforever.xyz`: `CONNECTING`, `IDLE`,
`STARTING_GAME`, `HOSTING`, held for over a minute, on a Hydra-minted token and
no other OAuth option. Use §5's installed launcher, not `./gradlew
:mock-client:run`, for the reasons §5 gives.

### Trusting a private certificate authority

If your lobby or Hydra sits behind a certificate the JDK does not trust — a test
environment with its own CA — nothing in the harness needs changing. Both the
token exchange and the WebSocket use the JDK `HttpClient`, which honours
`SSLContext.getDefault()`, so the standard JDK truststore properties apply:

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -jar mock-client-<version>-all.jar run ...
```

Build the truststore from the CA certificate once:

```bash
keytool -importcert -noprompt -alias test-ca \
  -file test-ca.pem -keystore truststore.jks -storepass changeit
```

Two practical notes. A truststore **replaces** the JDK's default rather than
adding to it, so if the run also reaches a host with a publicly trusted
certificate, start from a copy of `$JAVA_HOME/lib/security/cacerts` (default
password `changeit`) and import your CA into that. The usual case is a private CA
in front of the lobby only, leaving the Hydra token exchange on the public roots.
This covers what mock-client itself opens and nothing else: the ICE adapter runs
in its own JVM, launched with `-Dlogback.configurationFile` and no other system
property, so a truststore set here never reaches it. Its telemetry websocket is
its own affair. And these are JVM flags, not harness flags: they go before
`-jar`, and `./gradlew run` needs them passed through rather than appended to
`--args`.

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

This uses the refresh-token credential channel via `--config`. For a run driven
by a pre-signed access token instead, with no refresh token and no Hydra
exchange, see the access-token section of §3.

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
| `ice-smoke` exits `70` and you cannot tell why | `ice-smoke: FAIL [<verdict>] …` | `70` covers both a missing binary and busy ports. The verdict line distinguishes them: `PORTS_IN_USE` names the port to free, anything about the binary means the path is wrong. This is the no-account path's most common first failure. |
| `ice-smoke` reports `PORTS_IN_USE` on a CI runner | `ice-smoke: FAIL [PORTS_IN_USE] port pre-flight: …` | Something else on the runner holds `7236` or `7237` — the pre-flight only tests those two TCP ports, never the UDP lobby port — often a previous step's adapter that outlived it. Free the port, or move all three with `--ice-adapter-rpc-port`, `--ice-adapter-gpg-net-port` and `--ice-adapter-lobby-port` (§2a). |
| `ice-smoke` reports `ADAPTER_EXITED`, `RPC_SILENT` or `RPC_UNREACHABLE` and the adapter looks fine | `ice-smoke: FAIL [ADAPTER_EXITED] …` | Check the ports before the adapter — **on macOS**. The pre-flight tests by binding, and on Darwin a bind can succeed alongside an existing wildcard listener (measured against `0.0.0.0:7236`), so a busy port surfaces here instead. On Linux, including hosted runners, the pre-flight catches it and you get `PORTS_IN_USE` above. `lsof -i :7236` settles it. |
| `mock-game` exits `143` from a run that looked fine | `mock game started: …` with no `mock game finished` line | Nothing drove the game out of the lobby, so it waited as designed and your step timeout killed it. That is not a harness failure — a game sitting in a lobby is what a real one does. Bound it yourself with `timeout 30 java -jar …` if you want the wait to be your own. |
| The release jar you downloaded is not the one you expected | — | Verify it: releases cut after the checksum step landed carry a `.sha256` beside each jar (`sha256sum -c`), and the API exposes a per-asset `digest` for any release. If it is the wrong *version*, note that `releases/latest` skips drafts and prereleases — a release stays invisible to it until someone publishes the draft by hand, so a pipeline can keep pulling the previous one. |

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
   and `multi-peer-session`, whose two-peer evidence and full log-line table
   §9 draws on directly (its three- and four-peer evidence is not yet
   written up in this document — see the note at the end of §9). Both files
   now say this explicitly, so a reader is never following two versions of
   the same setup path.

## 9. Two-peer sessions (WBS 4.3.1)

Two Mock Clients on one machine, each with its own lobby account, its own
port set, its own real `faf-ice-adapter`, and its own real mock game, complete
a host/join **through the live lobby**. The clients never touch each other
in-process — the only value that crosses between them outside the lobby is
A's game uid, which is what an operator reads off A's own `game launch:` log
line, and which B needs to join.

This section is the followable path to it: one verified command
([§9.2](#92-running-two-clients)), `mock-client session`, that runs exactly
this — host, joiner, both adapters, both games, through the live lobby — and
passes or fails on its own. Everything else here is what a reader needs to
set up first and read afterwards — the second account, the log attribution,
and what the result should look like, evidenced by a real, verified run (see
[§9.5](#95-what-a-healthy-run-looks-like)). It covers what differs from a
single session (§3 credentials, §4 configuration, §5 running); it does not
restate them.

**Scope.** This section documents the two-peer case. `session` already runs
three and four peers the same way (WBS-4.3.3) — see the note at the end of
this section for why that shape isn't written up here yet.

### 9.1 The second identity

One account cannot host and join its own game, and logging in twice on one
account signs the first session out. `session` refuses two peers that would
share a credential file before either logs in, and refuses two peers on the
same account (on the access-token channel, where the account is checked
before login) the same way; a shared account on the refresh-token channel,
which is opaque, is instead caught at the second peer's `welcome`
(`checkDistinctAccount`, `mock-client/src/main/java/.../session/MultiPeerSession.java`).
Two peers need two distinct seeded test accounts and two distinct
credential files.

Bootstrap the second account exactly as §3 bootstraps the first — same Hydra
flow, same shared `foo` password, same one-time browser step — writing the
result to a second file instead of the first:

| Peer | Role | Refresh-token file |
|---|---|---|
| A | host | `.secrets/refresh_token.txt` |
| B | joiner | `.secrets/refresh_token_b.txt` |

Both files are gitignored and, like the single-session case, **rewritten in
place on every run** — Hydra rotates the refresh token on use, so never run
two sessions against the same account's file concurrently. (A pre-signed
access-token file works too, one per peer, on the channel §3 already
documents; a refresh-token file is the better default here since nothing
needs to keep it fresh by hand.)

### 9.2 Running two clients

**The verified path.** `session` (WBS-4.2.1) runs exactly this — host,
joiner, both real adapters, both real mock games, through the live lobby —
and passes or fails on its own, with nothing to assert by hand:

```bash
./gradlew :mock-client:installDist
./mock-client/build/install/mock-client/bin/mock-client session --config mock-client.json \
  --peers=2 \
  --peer-refresh-token-file=.secrets/refresh_token.txt,.secrets/refresh_token_b.txt
```

Run both from the repo root — the credential paths above are relative (§5).

`--config mock-client.json` supplies what §4 already has you write — the
lobby URL, OAuth endpoints, `uidBinaryPath`, and the adapter/game binary
paths, since every peer shares them — except its `oauthRefreshTokenFile` and
`oauthAccessTokenFile` fields, both of which `session` ignores entirely in
favour of the per-peer flags: one file per peer, host first, comma- or
flag-separated. `2` is `--peers`'s own default, shown only for clarity. A
pre-signed access-token file works the same way on `--peer-access-token-file`
instead (§9.1). `mock-client session --help` and
[`mock-client/README.md`](../../mock-client/README.md)'s own writeup carry
the full flag reference, the exit-code table, and the credential-layering
rule for when both flags are set — not restated here.

A clean run exits `0` and logs exactly one verdict line:

```text
session: PASS - 2 peers, full mesh and two-way game traffic, nothing left running
```

A failed checkpoint exits non-zero and logs `session: FAIL <peer>: <stage>:
<detail>` instead — the peer and stage name what did not happen (`welcome`,
`HOSTING`/`JOINING`, `full mesh`, `traffic`). A bad invocation (a missing
binary, fewer credential files than peers, two peers sharing one credential
file, or a `--log-level` above INFO, since INFO or finer is required, see
[§9.4](#94-per-instance-logs-and-attribution)) is refused before any peer
logs in, so a broken invocation never spends a rotated refresh token. Two
peers on one account are refused before login only on the access-token
channel; on the refresh-token channel this surfaces later, at the joiner's
`welcome` (§9.1), by which point both tokens have rotated.

**The same orchestration, if you would rather assert it than read a log
line.** `session` and `MultiPeerSessionLiveTest` both drive one
`MultiPeerSession`
(`mock-client/src/main/java/.../session/MultiPeerSession.java`); the test
runs three parameterized cases back to back — two, three and four peers —
where `session --peers 2` isolates just the first:

```bash
./gradlew :mock-client:integrationTest --tests '*MultiPeerSessionLiveTest*' --rerun
```

`--rerun` is not optional, for the reason given under §2's
`ClientGameLifecycleLiveTest` note. This path reads its credentials from the
environment rather than a flag — `FAF_REFRESH_TOKEN_A` / `FAF_REFRESH_TOKEN_B`,
defaulting to the same two files §9.1 has you bootstrap — and a missing
prerequisite **skips** the case it affects rather than failing it
(`missingPrerequisites`, `MultiPeerSessionLiveTest`); a skip is not a pass.

### 9.3 Port allocation

Each peer needs its own three adapter listener ports — the same three §2a
already documents the meaning of (`--ice-adapter-rpc-port`,
`--ice-adapter-gpg-net-port`, `--ice-adapter-lobby-port`) — and no two peers
may share one. `session` does not expose them as flags at all: it allocates
a fresh, OS-assigned free port set per peer and asserts no two peers share
one before either starts
(`freeAdapterPorts`/`checkDistinctPorts`,
`mock-client/src/main/java/.../session/MultiPeerSession.java`), and the
root `--ice-adapter-*-port` options are validated but not used by `session`
(§9.2). Port collision is something the command rules out on its own, not
something a reader manages.

**What a collision looks like.** Neither subprocess hangs silently — the one
that lost the race to bind logs its own failure: mock-game logs `failed to
bind lobby port`, and the adapter's JVM reports a `BindException` /
`Address already in use` for whichever of its two TCP ports was taken
(the same three strings `MultiPeerSessionLiveTest` greps for in a failed
wait). A benign TOCTOU window exists either way — ports are chosen free and
released before the subprocess binds them — so a collision can also come from
an unrelated process on a busy machine, not only from reusing A's set.

**Not ours to allocate.** The three ports above are listener ports the
harness picks. The actual UDP ports `faf-ice-adapter` opens per peer link for
ICE connectivity checks are chosen by the adapter itself, once negotiation
starts — there is no harness flag for them, and a reader looking for a fourth
port to configure will not find one.

### 9.4 Per-instance logs and attribution

A single-session run (§6) never needs to tell one client's lines from
another's. Two do, and `session` handles it entirely on its own — there is
nothing here for a reader to configure, only to know about when reading the
output.

`session` builds each peer's own instance label itself, `A` for the host and
`B`, `C`, … for each joiner in join order, and **refuses to start if
`INSTANCE_NAME` is already set** in its environment — a set value would
become every peer's fallback label, attributing every unlabelled line to one
peer (`SessionCommand.call`,
`mock-client/src/main/java/.../cli/SessionCommand.java`). It also **refuses
a `--log-level` above INFO** (WARN or ERROR): the game-traffic checkpoint
reads the games' own INFO progress lines, and neither the games nor this JVM
emit them above that level. INFO or finer is required; DEBUG and TRACE are
fine (`MultiPeerSession`'s constructor,
`mock-client/src/main/java/.../session/MultiPeerSession.java`). Both are
usage errors, refused before any peer logs in (§9.2) — a CI job driving
`session` hits the same two refusals and should not set either.

The affordance behind the labelling itself is `InstanceLabel`
(WBS-3.1.6.2/4.3.3): every log record — the client's own, its captured
adapter output, and its captured game output — carries an `instance` MDC
field naming the peer, and the JSONL contract exposes it as the `instance`
field on every record (`mock-client/README.md`'s harness log contract
documents its exact shape; not restated here). Every peer's own lines, its
adapter's, and its game's are therefore already attributable in the shared
`mock-client/logs/mockclient.jsonl` and each peer's own
`mock-client/logs/mockgame-<label>.jsonl` (one JSONL per game, since
concurrent games would otherwise share `mockgame.jsonl` — `MockGameLauncher`
forwards the label to the child it spawns for exactly this reason). Filter
on `instance` to read one peer, as `demos/README.md`'s log-line table
already shows.

The same underlying mechanism is available to a single `mock-client run`
process on its own — the one shape `session` itself refuses:
`LoggingSetup.configure` resolves the label from the `INSTANCE_NAME`
environment variable (or a `-DINSTANCE_NAME=…` system property, which wins),
and a named instance gets its own default log file,
`logs/mockclient-<label>.jsonl`, instead of the shared
`logs/mockclient.jsonl`. Source-verified (`LoggingSetup.java`), but not
exercised by `session` at all.

### 9.5 What a healthy run looks like

The stage order below is [`demos/README.md`](../demos/README.md)'s own
two-peer table — the `MultiPeerSessionLiveTest` evidence trimmed to A and its
joiner B, which is what that file already keeps for exactly this reason. It
is a real capture, not a reconstruction:

| Stage | Log line | Source |
|-------|----------|--------|
| A authenticated | `session ready: id=<idA> login=<loginA>` | `WelcomeStateSync` |
| A hosts | `Sending game_host for title=faf-test-harness 4.3.1 <uuid>` (the title is `faf-test-harness session <uuid>` on the `session` path §9.2 leads with, and `faf-test-harness 4.3.1 <uuid>` on the `MultiPeerSessionLiveTest` path) | `MockClientLifecycle` |
| A's session up | `game launch: uid=<uid> mod=faf name=…`, then `state entry: STARTING_GAME` | `MockClientLifecycle` |
| A's game in the lobby | `Received GPGNet message: GameState Idle` → `GameState Lobby` | `[ICEAdapter]` |
| A is hosting | `Sent GPGNet message: HostGame scmp_007`, then `state entry: HOSTING` | `[ICEAdapter]` / `MockClientLifecycle` |
| B authenticated | `session ready: id=<idB> login=<loginB>` | `WelcomeStateSync` |
| B joins | `Sending game_join for uid=<uid>`, then `game launch: uid=<uid> …` | `MockClientLifecycle` |
| B is joining | `state entry: JOINING` | `MockClientLifecycle` |
| A told about B | `peer connect: login=<loginB> id=<idB> offer=true` | `MockClientLifecycle` |
| Candidates crossing | `Sending ICE RPC request {…"method":"iceMsg"…}` on both sides | `IceAdapterConnection` |
| Peer states moving | `peer ice: local=<id> remote=<id> state=gathering` → `awaitingCandidates` → `checking` → `connected` | `IceEventLogger` |
| **The verdict** | `peer connected: local=<idA> remote=<idB> connected=true`, and the mirror image on B | `IceEventLogger` |
| Teardown | `state entry: TERMINATED` → `session teardown complete`, on both peers | `MockClientLifecycle` / `SessionTeardown` |

`offer=true` on A is the server's own doing (`connect_to_host` in
faf-server's `gameconnection.py` makes whichever side is already in the lobby
the ICE initiator), not something either client decides. The `peer ice`
states are informational — `completed` never arrives, because adapter 3.3.14
has no `setState(COMPLETED)` call site — which is why **the verdict line is
the one to wait for**, not a "final" ICE state. A run that never reaches it
on both sides has not established the session, regardless of how far the ICE
states got.

*Provenance: this table, and the two-peer case it describes, were verified
live against the real lobby on **2026-08-25** with two seeded accounts
(`test` as host, `Foo` as joiner) — recorded in
[`demos/README.md`](../demos/README.md#multi-peer-session-host-join-full-mesh-wbs-431-433),
which also carries the three- and four-peer evidence (2026-09-15), the full
acceptance-criteria mapping, and how to capture a fresh recording. Not
repeated here. The `session` command §9.2 leads with was separately verified
by a manual two-peer run from the release jars in an empty directory (#391):
exit `0` in 22 s, logging exactly
`session: PASS - 2 peers, full mesh and two-way game traffic, nothing left
running`, plus two evidenced failure shapes for a bad invocation.*

### 9.6 Known limitations

- **The lobby endpoint is public, but the path to it is still yours to prove.**
  `wss://ws.faforever.xyz` is Cloudflare-fronted and needs no VPN or
  allowlist, so a failure to reach it is local: DNS, a proxy, or an outbound
  firewall rule. §3/§8 found this worth confirming per-network; the same
  probe applies here.
- **The run needs two real, seeded test accounts**, each with its own
  bootstrapped refresh token (§9.1) — there is no way to exercise this
  section with only the single account §3 bootstraps.
- **Matchmaker violations are a different path from this one.** A live
  matchmaker run (queueing via `game_matchmaking`, out of scope for this
  section) can accrue violations on the accounts used; the custom-game
  host/join this section documents never touches that path. FAF's violation
  service penalises failing to connect to a game *after being matched*, not
  the act of queueing itself, so the exposure specific to running this
  harness live is two accounts matching each other in a matchmaker queue and
  then failing to connect — not anything §9.2's host/join session does. The
  first violation carries no penalty; bans escalate after that and are held
  in memory rather than the database. Modelling or predicting ban timing is
  out of scope here, as WBS-3.1.1.9 already scopes it out.

**Out of scope: three and four peers.** These already work — `session
--peers 3` and `--peers 4` are verified (WBS-4.3.3), `MultiPeerSessionLiveTest`
runs both cases (`demos/README.md`'s 2026-09-15 evidence above), and its
class javadoc documents the offer-direction asymmetry that starts at three
peers. What is still missing is this document's write-up of that shape — the
deterministic N-peer scheme, and what a healthy run looks like beyond two —
which depends on the N-client spawner (WBS-4.2.2, `mock-client/README.md`)
and is deferred to a later section appended here, not a restructure of what
exists above.

## 10. Network fault injection (WBS 5.1)

Two flags degrade the harness from the inside, covering the delayed-ICE and
dropped-UDP faults the project brief names. Both default to off, and with both
unset nothing about a run changes. Validation is by log inspection, which is
what the brief itself asks for.

Injection is in-harness rather than network-level on purpose. `tc`/`netem`
would run on a hosted Linux runner, but it cannot express either fault:
ICE candidates relay through the lobby over WSS, so degrading that socket also
degrades authentication and every other lobby message; and everything else
shares loopback, so `netem` on `lo` hits the control plane and all peers at
once. These flags target one relay and one sender, stay attributable per peer,
and work wherever the mocks themselves run.

| Flag | Component | Default | What it does |
|---|---|---|---|
| `--ice-relay-delay-ms` | mock-client | `0` | Holds every relayed ICE candidate for that many milliseconds before forwarding it, in both directions. |
| `--udp-drop-percent` | mock-game | `0` | Suppresses that percentage of outbound peer datagrams, drawn independently per peer per round. |

### `--ice-relay-delay-ms`

Delays ICE **signalling**, not the connectivity checks — those run adapter to
adapter inside `faf-ice-adapter` and are not ours to touch. Delaying candidate
relay delays when negotiation can begin, which is the faithful reading of the
requirement.

Candidates are delayed, never dropped and never reordered: the scheduler is
single-threaded and every forward takes the same delay, so they come out in the
order they went in. Malformed frames are still rejected immediately, on the
reader thread, so a bad candidate does not occupy a delay slot.

What to look for when it is on:

- The gap between the adapter's `onIceMsg` notification and the outbound
  `IceMsg` frame to the lobby widens by roughly the configured delay, in both
  directions. Compare timestamps on adjacent records in `logs/mockclient.jsonl`.
- The per-peer ICE connection-state transitions logged by WBS 3.1.6.2
  (`gathering` → `awaitingCandidates` → `checking` → `connected`) take
  correspondingly longer to reach `connected`.
- A two-peer session should still complete, **up to a ceiling**. In the pinned
  3.3.14 adapter the offerer (the host) arms a 6000 ms timer when it sends its
  candidates, and restarts ICE if the answer has not arrived by then. Two things
  have to fit inside that window:

  - **The baseline**, the time a healthy session takes with no delay injected.
    The offer crosses the lobby to the joiner, the joiner's adapter gathers its
    own candidates, and the answer crosses the lobby back. On a first attempt,
    about 0.85 s of that is the joiner adapter's ice4j setup, paid once per
    adapter process, so retries start faster. STUN against the adapter's three
    built-in public servers adds a few hundred ms, even though the mock client
    sends an empty `setIceServers` list, and the two lobby crossings take about
    0.85 s together. Injected delay adds to the baseline; it does not absorb
    any of it.
  - **The injected delay, once per relay pass.** The offer passes through the
    host's client and the joiner's client, and the answer passes back through
    both: four passes when both clients set the flag, two when one does.

  So the usable delay is `(6000 ms - baseline) / passes`.

  Measured on 2026-09-13 (adapter 3.3.14, live lobby, both peers on one host in
  one session, STUN only, n=5), the host spent 1982 to 2023 ms in
  `awaitingCandidates` at zero delay, and #343 reports a 2148 ms sample. Taking
  ~2150 ms as the baseline:

  | | passes | arithmetic ceiling | keep the delay under |
  |---|---|---|---|
  | both clients set the flag | 4 | ~960 ms | ~500 ms |
  | one client sets it | 2 | ~1900 ms | ~1000 ms |

  "Keep under" is about half the arithmetic ceiling, and both rows were
  verified live at that value.

  Treat the measured baseline as a lower bound. A real FAF client also passes
  TURN servers via `setIceServers`, which the adapter harvests, and real
  networks add latency to both lobby crossings, so the ceiling between two real
  machines is tighter, and so is the ceiling on a CI runner, which sits further
  from the lobby and shares its CPU. If the joiner's gathering hits the
  adapter's 5000 ms gathering cap, it sends no answer at all and ICE restarts
  whatever the flag is set to.

  To find your own baseline, run once with the flag at `0` and take the gap
  between `peer ice: ... state=awaitingCandidates` and `state=checking` in the
  host's log (the host is the side that logs `peer connect: ... offer=true`),
  then apply the formula. Do this on the machine that will run the delay,
  including a CI runner, or stay at a few hundred milliseconds there.

  Past the ceiling you get an ICE restart loop rather than slow negotiation: on
  the host, `awaitingCandidates` turns to `disconnected` almost exactly 6000 ms
  later, and `gathering` follows about 5 s after that, so each failed attempt
  takes about 11 s. That is a different phenomenon, and not the one the flag is
  for. Just past the ceiling a session can still connect on a retry, because
  the retry skips the one-time ice4j setup, so one pass near the ceiling does
  not prove a value is safe. Start a two-peer manual run at a few hundred
  milliseconds.

### `--udp-drop-percent`

Suppresses the datagram at the same point a send failure is swallowed, after
the sequence number has been stamped and advanced. That ordering is
load-bearing: `GameUdpReceiver` measures loss against the sequence, so a drop
that skipped the increment would leave the receiving peer with an unbroken
stream and the injected fault would be invisible.

What to look for when it is on:

- The sending game states the percentage at `INFO` when its traffic starts:
  `peer traffic started: one datagram per peer every 100 ms, dropping 25%`.
  That line is the check that the flag took effect; the per-datagram evidence
  below is `DEBUG` only.
- The receiving peer's loss ratio for that sender tracks the percentage. Read
  three numbers from the receiving game's log: `S` from
  `first datagram from sender <id> (seq S)`, and the received count `N` and
  highest sequence `H` from
  `game UDP receiver stopped; sender <id> totals: received N, highest sequence H`,
  logged when the game shuts down in an orderly way. In an orchestrated run
  that line reaches only the game's own `logs/mockgame.jsonl` (§6), not the
  client's output: the client stops relaying the game's stream during
  teardown, just before it is written. Mid-run, or after a kill that skipped
  shutdown, use the last
  `player <receiver> peer traffic from player <sender>` progress line instead,
  which can read one datagram behind. The loss ratio is
  `(H - S + 1 - N) / (H - S + 1)`. Counting from `S` rather than from zero
  leaves out datagrams sent before the ICE link was up, which would otherwise
  read as loss at every percentage, `0` included.
- Do not use the `gaps` count. It rises once per gap, not once per lost
  datagram, so five consecutive drops count as one. For independent drops at
  probability `p` its expectation is `n·p·(1 - p)`: it peaks at 50% and falls
  back to zero at 100%, where nothing arrives at all.
- Caveats on the ratio. At `100` the receiver never sees that sender, so it
  logs no line for it; the evidence is the other direction still flowing plus
  the sender's `DEBUG` drop records below. Drops after the last received
  datagram are not counted, which biases the ratio slightly low. A peer
  re-registered at a changed address restarts its sequence at zero, so the
  ratio only holds within one registration.
- The loss is attributable to the sender that dropped it, because the counts
  are kept per sender id. That is the whole reason injection sits here rather
  than on the interface, where loss is traceable to nobody.
- At `DEBUG`, the sender emits one `dropping datagram seq=…` record per
  suppressed datagram, naming the peer and the sequence. Use it to tie a
  specific gap at the receiver to the send that never happened. It is `DEBUG`
  rather than `WARN` because an injected fault is the operator's own doing, and
  at a high percentage a per-datagram `WARN` would bury the rest of the run.

**Fidelity limits.** Two things this fault does not model, both checked against
upstream source:

- The adapter will not notice. In the pinned 3.3.14 `java-ice-adapter`, link
  liveness comes from `PeerConnectivityCheckerModule`: the offering side sends
  its own echo every 1000 ms over ICE and declares the connection lost after
  10000 ms without one coming back. Echoes and game data are separate packet
  types, and only echoes reset that clock, so none of it passes through the
  game's socket. Even at `100` the adapter reports a healthy link. Expect
  silence at the receiving game, not an ICE disconnect.
- Real loss is probably not permanent. Forged Alliance's 15-byte engine packet
  header, as mirrored by `faf-pioneer` (`moho/packet.go` at `64dcc34`), carries
  a sequence number, an expected sequence number, an in-response-to field and
  an early-arrival mask, and faf-pioneer tracks a resend count per packet
  alongside it. That is the shape of a reliability layer, so real loss more
  likely shows up as delay and resends than as a permanent hole. The mock drops
  for good and never resends. That is enough to exercise per-peer loss
  detection, not to reproduce how a real game degrades.

An orchestrated run reaches the same fault through the mock client:
`--mock-game-udp-drop-percent` on `mock-client` is passed straight through to the
mock-game it launches as `--udp-drop-percent` (WBS-5.1-fix, #322). It is emitted
only when non-zero, so a default run produces the argv it always produced. Both
spellings exist because both callers do: `mock-game` takes its own flag when run
by hand.

## 11. A session in a consumer's CI (WBS 4.2.1)

§2a and §3 give a CI reader the jars and the credentials. This section is the
job that uses them: a maintainer who wants a build of their own
`faf-ice-adapter` driven through a real two-peer session against the live
lobby, unattended, and a verdict they can read afterwards.

The run itself is one command, from the published jars and nothing else. No
clone, no Gradle:

```bash
java -jar mock-client-<version>-all.jar \
  --lobby-websocket-url=wss://ws.faforever.xyz \
  --unique-id=00000000-0000-0000-0000-000000000000 \
  --uid-binary-path=./faf-uid \
  --ice-adapter-binary-path=./your-adapter-build.jar \
  --mock-game-binary-path=./mock-game-<version>-all.jar \
  session \
    --peers=2 \
    --peer-access-token-file=./host.txt \
    --peer-access-token-file=./joiner.txt
```

The job below is that command with everything a runner has to provision around
it. It is this repository's own `session` job
([`live-integration.yml`](../../.github/workflows/live-integration.yml)) with
its Gradle steps gone: it downloads the mock-client and mock-game jars from
`releases/latest` ([§2a](#2a-the-jar-only-path-no-clone)) instead of building
them, checks the jar carries the flag it is about to use, and points
`--ice-adapter-binary-path` at the adapter it just built instead of the pinned
one. The evidence-summary step is dropped, the two `faf-uid` steps are merged,
the job cap is raised to cover the consumer's own build step, and the log
upload fires on a cancelled run as well as a failed one.

The flag reference, the credential-layering rule and the full exit-code table
are [`mock-client/README.md`](../../mock-client/README.md)'s, and are not
restated here; the by-hand walkthrough for two peers lands in §9 with R79b. Two
refusals catch a job as readily as a person: `INSTANCE_NAME` must be unset,
since `session` labels each peer itself, and `--log-level` must be INFO or
finer, since the traffic checkpoint reads the games' own INFO lines. Both are
refused before any process starts.

The host advertises its game with `friends` visibility (`MultiPeerSession`), so
a dispatch does not put a test game on the public list. The joiners never need
to find it: the session hands each one the host's game uid directly.

### The job

Copy it into `.github/workflows/` in your own repository. Two things are yours:
the adapter build, and the two secret names.

It runs on manual dispatch, as this repository's own job does and for the same
reason: the shared test lobby's availability is outside your control, so a red
run is a finding rather than a reason to block a merge. Add a `schedule` or a
`push` trigger if you want it to run on its own, and keep it out of your
required checks either way.

```yaml
name: FAF harness session (advisory)

on:
  workflow_dispatch:

permissions:
  contents: read

# Keyed on the accounts, not the branch, and queueing rather than cancelling. See below.
concurrency:
  group: faf-harness-session-accounts
  cancel-in-progress: false

jobs:
  session:
    name: Two-peer session against the FAF test lobby
    runs-on: ubuntu-latest
    # Above every step timeout below it (21 of provisioning, 15 of build, 12 of session, 6 of
    # cleanup and upload) with room for the runner's own setup and post-steps, which no step
    # timeout bounds. A job cap cancels the run rather than failing a step, so a session caught by
    # it never reports its verdict at all.
    timeout-minutes: 60

    env:
      HARNESS_REPO: md-173/faf-test-harness
      # The pin this repository runs is in .github/workflows/live-integration.yml. Check there,
      # or FAForever/uid's releases, before carrying these two forward.
      FAF_UID_VERSION: v4.0.7
      FAF_UID_SHA256: 1136d0e1cd7e61682ad375043fdac4bae690bf63d7fdd98a7a3a1fb9ccac61da
      # The session's working directory; its logs land in session-run/logs/.
      WORK: ${{ github.workspace }}/session-run

    steps:
      # First, so a missing or dead token fails in seconds rather than after a build. Tokens come
      # from the environment, never argv, so none reaches the process table.
      - name: Write one token file per peer
        timeout-minutes: 1
        env:
          FAF_ACCESS_TOKEN_HOST: ${{ secrets.FAF_ACCESS_TOKEN_HOST }}
          FAF_ACCESS_TOKEN_JOINER: ${{ secrets.FAF_ACCESS_TOKEN_JOINER }}
        run: |
          umask 077
          # Outside the workspace, so no checkout and no upload glob can reach them.
          TOKENS="$RUNNER_TEMP/faf-tokens"
          echo "TOKENS=$TOKENS" >> "$GITHUB_ENV"
          mkdir -p "$TOKENS"
          missing=""
          [ -n "$FAF_ACCESS_TOKEN_HOST" ] || missing="$missing FAF_ACCESS_TOKEN_HOST"
          [ -n "$FAF_ACCESS_TOKEN_JOINER" ] || missing="$missing FAF_ACCESS_TOKEN_JOINER"
          if [ -n "$missing" ]; then
            echo "::error::token secret not set:$missing"
            exit 1
          fi
          # The harness reads no expiry on purpose, so that the lobby's own rejection is never
          # flattened into a local guess (section 3). This is pre-flight rather than the harness:
          # it refuses only a token that cannot possibly work, and every other verdict stays the
          # lobby's. Only the expiry is ever printed.
          python3 - <<'PY'
          import base64, json, math, os, sys, time

          # Everything between here and the session's first login is provisioning and your own
          # build. A token with less than this left may not survive to be used.
          WARN_BELOW = 15 * 60
          expired = []
          for name in ("FAF_ACCESS_TOKEN_HOST", "FAF_ACCESS_TOKEN_JOINER"):
              try:
                  header, segment, signature = os.environ.get(name, "").split(".")
                  claims = json.loads(
                      base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
              except Exception:
                  claims = None
              if not isinstance(claims, dict):
                  # An opaque token carries no readable claims and may still be good.
                  print("::warning::%s carries no readable claims; the lobby decides" % name)
                  continue
              if "exp" not in claims:
                  print("%s carries no exp, which the lobby accepts" % name)
                  continue
              exp = claims["exp"]
              try:
                  # A bool is an int, and a number too large for this platform's float is one the
                  # lobby would still compare, so neither is read here as an expiry.
                  left = float(exp) - time.time()
                  usable = math.isfinite(left) and not isinstance(exp, bool)
              except (OverflowError, TypeError, ValueError):
                  usable = False
              if not usable:
                  print("::warning::%s has an exp that is not a usable number, which the lobby "
                        "rejects outright; mint a fresh one" % name)
              elif left <= 0:
                  print("::error::%s expired %d minutes ago; mint a fresh one" % (name, -left // 60))
                  expired.append(name)
              elif left < WARN_BELOW:
                  print("::warning::%s has %d minutes left and may expire mid-run"
                        % (name, left // 60))
              else:
                  print("%s is good for %d more minutes" % (name, left // 60))
          if expired:
              sys.exit(1)
          PY

          printf '%s' "$FAF_ACCESS_TOKEN_HOST" > "$TOKENS/host.txt"
          printf '%s' "$FAF_ACCESS_TOKEN_JOINER" > "$TOKENS/joiner.txt"

      - uses: actions/checkout@v7
        timeout-minutes: 5
        with:
          # The job runs downloaded jars, including an adapter build, beside this checkout, and
          # needs no git credentials of its own. Do not persist one here.
          persist-credentials: false

      - name: Set up JDK 21
        uses: actions/setup-java@v6
        timeout-minutes: 5
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Download the harness jars
        timeout-minutes: 5
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          mkdir -p "$RUNNER_TEMP/harness"
          cd "$RUNNER_TEMP/harness"
          # `gh` is on every hosted runner and reads the token from the environment, so nothing
          # reaches the process table. Authenticated because the anonymous API allows 60 requests
          # an hour per IP and runners share theirs; the token buys the rate limit, not access,
          # since the harness repository is public.
          gh api "repos/$HARNESS_REPO/releases/latest" > release.json
          gh release download --repo "$HARNESS_REPO" --pattern '*-all.jar' --clobber
          # Both jars from one release: the traffic check parses mock-game's own progress line.
          jq -r '.assets[] | select(.name | endswith("-all.jar"))
                 | (.digest // "") + "  " + .name' release.json > digests.txt
          grep -q '^sha256:' digests.txt \
            || { echo "::error::the release published no asset digests"; exit 1; }
          sed 's/^sha256://' digests.txt | sha256sum --strict -c -
          shopt -s nullglob
          set -- mock-client-*-all.jar
          [ "$#" -eq 1 ] || { echo "::error::expected one mock-client jar, found $#"; exit 1; }
          echo "CLIENT_JAR=$PWD/$1" >> "$GITHUB_ENV"
          set -- mock-game-*-all.jar
          [ "$#" -eq 1 ] || { echo "::error::expected one mock-game jar, found $#"; exit 1; }
          echo "GAME_JAR=$PWD/$1" >> "$GITHUB_ENV"

      # releases/latest can be older than you expect, so ask the jar rather than trust the tag.
      - name: Check the release carries per-peer access tokens
        timeout-minutes: 2
        run: |
          java -jar "$CLIENT_JAR" session --help | grep -q -- '--peer-access-token-file' || {
            echo "::error::$(basename "$CLIENT_JAR") predates --peer-access-token-file"
            exit 1
          }

      # faf-uid reads DMI, BIOS and CPU details a cloud VM may withhold, so probe it before the
      # session depends on it. Its output is an identity blob: counted here, never printed.
      - name: Download and verify faf-uid
        timeout-minutes: 3
        run: |
          FAF_UID_BINARY="$RUNNER_TEMP/bin/faf-uid"
          echo "FAF_UID_BINARY=$FAF_UID_BINARY" >> "$GITHUB_ENV"
          mkdir -p "$(dirname "$FAF_UID_BINARY")"
          curl -fsSL --retry 3 --retry-all-errors -o "$FAF_UID_BINARY" \
            "https://github.com/FAForever/uid/releases/download/${FAF_UID_VERSION}/faf-uid"
          echo "${FAF_UID_SHA256}  ${FAF_UID_BINARY}" | sha256sum -c -
          chmod +x "$FAF_UID_BINARY"
          set +e
          uid=$("$FAF_UID_BINARY" 1 2>"$RUNNER_TEMP/faf-uid.stderr")
          code=$?
          set -e
          if [ "$code" -ne 0 ] || [ -z "$uid" ]; then
            echo "::error::faf-uid exited $code with ${#uid} chars of output;" \
              "every login in this session would fail"
            head -c 200 "$RUNNER_TEMP/faf-uid.stderr"
            exit 1
          fi
          echo "faf-uid produced a ${#uid}-character unique_id"

      # Yours to replace, and last of the provisioning on purpose: everything cheaper than it has
      # already failed by here. The session needs one path to one headless adapter jar; how you
      # build, fetch or cache it is your business. Export it as ADAPTER_JAR.
      - name: Build the adapter under test
        timeout-minutes: 15
        run: |
          echo "ADAPTER_JAR=$GITHUB_WORKSPACE/<your headless adapter jar>" >> "$GITHUB_ENV"

      # A backstop, not the bound that matters: the session has its own deadline and verdict.
      - name: Run a two-peer session
        timeout-minutes: 12
        run: |
          umask 077
          mkdir -p "$WORK"
          cd "$WORK"
          java -jar "$CLIENT_JAR" \
            --lobby-websocket-url=wss://ws.faforever.xyz \
            --unique-id=00000000-0000-0000-0000-000000000000 \
            --uid-binary-path="$FAF_UID_BINARY" \
            --ice-adapter-binary-path="$ADAPTER_JAR" \
            --mock-game-binary-path="$GAME_JAR" \
            session \
              --peers=2 \
              --peer-access-token-file="$TOKENS/host.txt" \
              --peer-access-token-file="$TOKENS/joiner.txt"

      # On every path, including this step timing out.
      - name: Remove the token files
        if: always()
        timeout-minutes: 1
        run: rm -rf "${TOKENS:-$RUNNER_TEMP/faf-tokens}"

      # Cancellation counts: a run cancelled mid-session is the one whose logs matter most. They
      # carry the client's lines with every adapter's and game's captured output.
      - name: Upload the session logs
        if: failure() || cancelled()
        uses: actions/upload-artifact@v7
        timeout-minutes: 5
        with:
          name: session-logs-${{ github.run_id }}-${{ github.run_attempt }}
          path: ${{ env.WORK }}/logs/*.jsonl*
          retention-days: 14
          if-no-files-found: warn
```

### What the job can branch on

Three exit codes, not two. A job that treats them as two reports its own
mistakes as somebody else's.

| Exit | What it means | What the job should do |
|---|---|---|
| `0` | A full mesh, two-way game traffic between every pair, and no adapter or game left running. | Pass. |
| `70` | A checkpoint failed, logged as `session: FAIL <peer>: <stage>: <detail>`, or a subprocess survived teardown and was killed, or an exception escaped the command. | Read the stage before filing anything. See below. |
| `2` | A bad invocation: no credential list, fewer credential files than peers, two peers on one file or one account, an unreadable or empty file, a missing binary, `INSTANCE_NAME` set, or `--log-level` above INFO. | Fix the job. Nothing started, so there is nothing to clean up. |

Every `2` is refused before any process starts, which is what makes the
distinction worth keeping: a job that folds `2` into `70` reports its own
misconfiguration as a harness failure, and blames an adapter that never ran.
That list is the job-facing subset;
[`mock-client/README.md`](../../mock-client/README.md) carries every cause.

The stage in a `70` says whose fault it is, and three of the eight are never
the adapter's: `ports` is the runner, `shutdown` is a run cancelled before a
peer started, and `welcome` is a credential or the lobby. The other five,
`game_launch`, `HOSTING`, `JOINING`, `full mesh` and `traffic`, are the ones
that belong to the adapter under test. The peer and the stage are named in the
log line, not in the exit status, so a job cannot branch on them. Keep the log.

Two codes below the harness are not session verdicts at all: a cancelled or
killed run exits on its signal, `130` or `143`, and a JVM `Error` exits `1`.

### What the job needs

- **One credential per peer, still valid when the job runs.** `session` takes
  one file per peer, and an unattended job wants the pre-signed channel,
  `--peer-access-token-file`. What such a token must carry, and what a
  rejection looks like, is
  [§3](#the-other-credential-channel-a-pre-signed-access-token); how this
  repository mints and stores its own is
  [`CONTRIBUTING.md` §3](../../CONTRIBUTING.md#the-live-integration-workflow-manual-advisory).
  Nothing in the harness reads a token's expiry; the one claim it does read is
  `sub`, to refuse two peers on one account before either logs in. An
  expired token is therefore sent as-is and refused by the lobby at the
  `welcome` stage, once the job has already paid for everything else, which is
  why the job decodes `exp` itself in its first step. Mint each token after the
  queue clears rather than before it: the `concurrency` group makes a second
  dispatch wait, and a token minted at dispatch time can be dead by the time
  the run starts.
- **Not `--peer-refresh-token-file`, whatever it is worth at a terminal.**
  Hydra rotates a refresh token on every use, the session rewrites the file in
  place with the rotated value, and on a runner that file dies with the job. So
  every run spends the secret, the stored copy is stale the moment that peer
  logs in, and the account needs a browser re-bootstrap before the next run.
  The by-hand section for a person will land in §9 recommending the opposite,
  and will be right to: there the rewrite is the point, because nothing has to
  be kept fresh by hand. Unattended, that same rewrite is the whole cost.
- **Test accounts nothing else uses, and a `concurrency` group keyed on them.**
  A second login as the same account signs the first out, fatally, so a local
  run and a dispatch on the same account kill each other. A dispatch can name
  any ref, so a group keyed on the branch would let two runs overlap on one
  account anyway. `CONTRIBUTING.md` §3 records the same two rules for this
  repository's own CI accounts.
- **Both jars from one release, and a release that carries the flag.** The
  traffic checkpoint parses mock-game's own progress line, so a mock-game from
  a different release leaves a full mesh the checkpoint cannot confirm, and the
  session fails at `traffic` for a reason that has nothing to do with your
  adapter. `releases/latest` skips drafts and prereleases, so it can be older
  than the commit you are reading. Ask the jar rather than trust the tag, as
  the check step does. Without that step, a jar lacking the subcommand answers
  the session invocation with `Unmatched arguments`, naming `session` and
  everything after it, and exits `2` after the adapter build. (When this
  section was written, `releases/latest` was 0.2.0, which predates both
  `session` and `--peer-access-token-file`.)
- **A placeholder `--unique-id` and a real `faf-uid`.** Both are needed, for
  the reason §3 gives. On a runner the failure is easy to misread: without the
  binary the lobby's policy request fails and the login ends in
  `{"command":"invalid"}`, which looks like an ordinary auth failure. That is
  what the probe step exists to pre-empt.
- **Room for the session's own deadline.** `session` bounds itself at 420 s
  from the first login, tears down outside that bound, and reports its own
  verdict. A step timeout below the two together turns a reportable failure
  into a killed step with no verdict at all, so leave headroom: twelve minutes
  for two peers, as above. The deadline does not yet grow with `--peers`, and
  #87's ceiling step will resize it
  ([`mock-client/README.md`](../../mock-client/README.md)), so re-read this
  bullet when it does.
- **Somewhere for the token files that nothing else can reach.** The job writes
  them under `RUNNER_TEMP`, outside the workspace, so no checkout and no upload
  glob can reach them, and deletes them on every path. A hosted runner is
  destroyed afterwards anyway; a self-hosted one is not, and a hard-killed job
  never reaches its cleanup step, so treat each file as a live bearer
  credential until its token expires.

### What the run does not cover

- **The TURN relay path.** mock-client sends an empty `setIceServers` list
  (`MockClientLifecycle`), so the adapter is given no TURN server and no relay
  candidate can be gathered. It still uses the three built-in public STUN
  servers whose cost §10 measures, and §10 also records what a real client
  passes instead.
- **NAT traversal.** Both peers run on one runner, so the pair connects over
  that machine's own interfaces. The run proves the client, adapter and game
  path end to end, and that the adapter forwards game packets in both
  directions, not that it gets through anything.
- **That each peer really used `faf-uid`.** The probe step proves the binary
  runs on this runner. If it later fails for a peer, the client falls back to
  the placeholder `unique_id` with only a WARN, and the lobby ignores the
  policy verdict, so such a run can still pass. This repository's own job greps
  its log for one `faf-uid` line and one login per peer, and warns when either
  is short; a consumer who wants that assurance has to add the same check.

*Provenance. Every `run:` step above was executed on **2026-09-19** on WSL2
Linux, in order, the way a runner hands them to bash and with `GITHUB_ENV`
carried between them: the token write and its expiry check, the download and
its digest check, the subcommand check, the `faf-uid` download, checksum and
probe, the session itself, and the token removal, which left its directory
gone. The session step ran against the live lobby, two peers on pre-signed
access tokens for two seeded accounts, with `faf-ice-adapter` 3.3.14: exit `0`
in 18 s, logging `session: PASS - 2 peers, full mesh and two-way game traffic,
nothing left running`, with its four log files under the path the upload step
collects and no adapter or game process left behind. The expiry check was
exercised separately across seventeen token shapes, from a valid token to an
expired one, a `null`, a string, an `Infinity` and a 401-digit integer. The
file passes `actionlint` with `shellcheck`.*

*Two substitutions stood in for what cannot run here. `ADAPTER_JAR` was set
directly, since the adapter build is the consumer's own step; and the jar paths
were repointed after the download step at jars built from this branch, since
`releases/latest` is the 0.2.0 that the subcommand check correctly refuses. So
the download route is verified while the artifact it yields today is not the
one this section describes. Two things were not done at all and are not
claimed: the job has never been dispatched on a runner, which needs the
repository's own secrets, and the four `uses:` steps have never run.*
