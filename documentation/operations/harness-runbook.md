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
  configured`. Have a real JDK 21 on the machine (this runbook's live
  commands in §2 ran against an auto-detected local Temurin 21; the Temurin
  25 elsewhere on that machine was only ever the Gradle daemon's own JVM).
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
project's CI: no account, no OAuth, no network beyond localhost, and the
sequence below was executed to produce this section (Windows 11, Gradle
daemon on Temurin 25 with the actual compile/run on an auto-detected local
Temurin 21, 2026-08-21, `faf-ice-adapter` 3.3.14).

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
process. Two independent things block it as of this writing:

1. `mock-game`'s `main` is still a stub that logs one line and exits `0`
   immediately, before ever opening the GPGNet socket (recorded in
   [`component-isolation.md`](component-isolation.md), row 5) — verified
   again here: `launch-game` against the built `mock-game` binary exits `70`
   (`RUNTIME`) because the game exits before its run window.
2. Independently of that, `faf-ice-adapter` 3.3.14 blocks its GPGNet accept
   path (`RPCService.getPeerOrWait()`) until a JSON-RPC client connects first
   — `launch-ice` alone never provides one, so even a real game binary would
   hang on connect. This ordering constraint is documented in full in
   `GpgNetConnectionLiveSmokeTest`'s class javadoc (WBS 3.2.2.4).

The GPGNet handshake itself **is** proven, against the real adapter, by that
same automated test — `GpgNetConnectionLiveSmokeTest`
(`./gradlew :mock-game:integrationTest --tests '*GpgNetConnectionLiveSmokeTest'`),
which drives the protocol in-process and holds a plain TCP socket on the RPC
port to satisfy precondition 2 above. It self-skips (does not fail) when the
adapter jar or network is absent, so a skip there is the expected off-network
result, not a failure — see [`component-isolation.md`](component-isolation.md)
for the wider `test` / `integrationTest` split this and the other live rows
follow. Treat that test, not a manually composed `launch-ice` + `launch-game`
pair, as the current evidence for "mock game talks to a real adapter." A
`launch-ice`-only run, as captured above, is the right and sufficient
adapter-alone check for a CI embedding this release today.

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
   `demos/README.md` remains the sprint-review evidence record for the
   `lobby-connect-idle` demo specifically (its captured transcript, its
   acceptance-criteria mapping, and how to capture a fresh recording), and is
   linked from §5/§6 above for exactly that evidence. Both files now say this
   explicitly, so a reader is never following two versions of the same setup
   path.

## 9. Multi-peer sessions (R79b)

Running two or more Mock Client instances on one box — per-instance ports,
log attribution, the `INSTANCE_NAME` convention, and true N-peer sessions —
is out of scope for this document. It lands with R79b, immediately after the
two-peer and N-peer cards, as sections appended here rather than a
restructure of what exists above.
