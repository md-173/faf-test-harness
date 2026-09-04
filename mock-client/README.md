# Mock Client

Headless CLI stand-in for the FAF desktop client. Connects to the lobby server,
launches `faf-ice-adapter` and `mock-game` as subprocesses, and proxies GPGNet
traffic between them and the lobby. Used for end-to-end integration tests that
do not require a real game install or a human at the keyboard.

## Subcommands

Mock Client is a Picocli command tree: a root `mock-client` command plus four
subcommands that dispatch to the matching component.

| Subcommand    | Purpose                                                                            | Needs a FAF account |
|---------------|------------------------------------------------------------------------------------|---|
| `run`         | Connect to the lobby, authenticate, and sit idle until interrupted.                 | yes |
| `launch-ice`  | Spawn `faf-ice-adapter` only and forward its output through the harness logger.    | no¹ |
| `launch-game` | Spawn `mock-game` only and forward its output through the harness logger.          | no¹ |
| `ice-smoke`   | Bring up the adapter, verify its JSON-RPC and GPGNet endpoints are serving, tear it down. | no |

`run` (WBS-3.1.1.4) connects to the lobby, runs the auth handshake
(`ask_session → session → auth → welcome`), hydrates the welcome state, logs the
authenticated player id, and then sits idle — the transport auto-replies `pong`
to the lobby's `ping` heartbeats. `Ctrl-C` / `SIGTERM` closes the WebSocket
cleanly (the process exit code then follows the signal: 130 for SIGINT, 143 for
SIGTERM). `launch-ice` (WBS-3.1.2.2) and `launch-game` (WBS-3.1.2.3) each spawn
their respective binary, run it for `--duration-seconds`, terminate it, and log
the exit code. `ice-smoke` (WBS-3.1.4.3) is the reachability gate: it spawns the
adapter, connects to its JSON-RPC port, sends one request, connects to its GPGNet
port and waits for the adapter to announce that connection back over RPC, then
tears everything down. Exit `0` means reachable; any other exit names the phase
that failed. A healthy run takes about two seconds.

¹ `launch-ice` and `launch-game` never contact the lobby, but they are still
validated as full-session invocations, so they require syntactically valid
placeholders for the lobby and OAuth options (see the worked example in
[`harness-runbook.md`](../documentation/operations/harness-runbook.md) §2).
`ice-smoke` deliberately does not: it validates only the adapter options, so it
runs with no credentials of any kind.

Invocation shape:

```text
mock-client [global flags] <subcommand> [subcommand flags]
```

Global flags — `--config`, `--help`, `--version`, plus the 32 config options —
are declared on the root and apply to every subcommand. Each
subcommand also accepts its own `--help`. `launch-ice` and `launch-game`
additionally take a subcommand-local `--duration-seconds` flag, and `ice-smoke`
a `--timeout-seconds` flag.

## Exit codes

| Code | Constant          | When                                                                             |
|------|-------------------|----------------------------------------------------------------------------------|
| `0`  | `OK`              | Successful run; `--help` and `--version`. For `ice-smoke`: the adapter is reachable. |
| `2`  | `USAGE`           | Bad invocation: invalid args, missing required options, unknown subcommand, no subcommand, unreadable config file, malformed JSON, bad URI, bad port. |
| `70` | `RUNTIME`         | A runtime failure after a subcommand started — e.g. `run` had no usable refresh-token file or the lobby session failed, `launch-ice` / `launch-game` could not find/start its binary or the child exited before its run window, or `ice-smoke` returned any verdict other than reachable. |

No subcommand returns `64` (`NOT_IMPLEMENTED`) — the constant no longer exists.
Nothing shipped here is a placeholder.

`USAGE` matches picocli's default `CommandLine.ExitCode.USAGE` so picocli's
parameter-exception path needs no remap. Constants live in
`com.faforever.testharness.client.cli.ExitCodes`.

## Configuration

Every Mock Client component reads from a single `MockClientConfig` object
produced by `ConfigLoader`. The loader is built on [picocli][picocli], which
parses CLI flags, then resolves any unset values via environment variables, an
optional JSON config file, and built-in defaults. No other code calls
`System.getenv`, `System.getProperty`, or reads the filesystem to discover
configuration.

[picocli]: https://picocli.info/

### Precedence

Settings are resolved per field from four sources, lowest to highest priority.
Higher sources override lower ones.

1. **Built-in defaults** — `@Option(defaultValue = ...)` on `MockClientCli`.
2. **Config file** — JSON, supplied with `--config <path>`.
3. **Environment variables** — `FAF_MOCK_CLIENT_*`, see convention below.
4. **CLI flags** — `--kebab-case`, see `--help` output.

### Environment variable convention

`FAF_MOCK_CLIENT_<UPPER_SNAKE_CASE>` of the JSON / CLI key. The `_CLIENT_`
segment disambiguates this module from `mock-game`, which uses
`FAF_MOCK_GAME_*` for its own configuration.

Examples:

| JSON key | Env var | CLI flag |
|---|---|---|
| `lobbyWebSocketUrl` | `FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL` | `--lobby-websocket-url` |
| `oauthClientId` | `FAF_MOCK_CLIENT_OAUTH_CLIENT_ID` | `--oauth-client-id` |
| `iceAdapterRpcPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_RPC_PORT` | `--ice-adapter-rpc-port` |

The mapping is mechanical: kebab-case → upper-snake-case for env vars,
kebab-case → camelCase for the JSON file.

### Field reference

The authoritative list of fields, defaults, env-var names, and CLI flags is
the output of `--help`:

```bash
./gradlew :mock-client:run --args="--help"
```

The table below is a quick reference. If it ever drifts from `--help`,
`--help` wins. **Required** there means required by the full-session config
validation, which `run`, `launch-ice`, and `launch-game` all share. `ice-smoke`
does not: it validates only the `iceAdapter*` / `player*` / logging fields, so
none of the lobby or OAuth rows apply to it.

| JSON key | Env var | CLI flag | Default | Required | Description |
|---|---|---|---|---|---|
| `lobbyWebSocketUrl` | `FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL` | `--lobby-websocket-url` | — | yes | WebSocket endpoint of the FAF lobby server. |
| `oauthTokenUrl` | `FAF_MOCK_CLIENT_OAUTH_TOKEN_URL` | `--oauth-token-url` | — | yes | OAuth2 token endpoint (Hydra `/oauth2/token`). |
| `oauthAuthEndpoint` | `FAF_MOCK_CLIENT_OAUTH_AUTH_ENDPOINT` | `--oauth-auth-endpoint` | — | yes | OAuth2 authorization endpoint, used by the one-time refresh-token bootstrap. |
| `oauthRedirectUri` | `FAF_MOCK_CLIENT_OAUTH_REDIRECT_URI` | `--oauth-redirect-uri` | — | yes | Redirect URI registered on the OAuth client. |
| `oauthScopes` | `FAF_MOCK_CLIENT_OAUTH_SCOPES` | `--oauth-scopes` | — | yes | Space-separated OAuth2 scopes (e.g. `openid offline lobby`). |
| `oauthClientId` | `FAF_MOCK_CLIENT_OAUTH_CLIENT_ID` | `--oauth-client-id` | — | yes | OAuth2 public client identifier. |
| `oauthRefreshTokenFile` | `FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE` | `--oauth-refresh-token-file` | — | yes¹ | Path to the file holding the long-lived refresh token (sensitive); rewritten atomically on each rotation. |
| `uniqueId` | `FAF_MOCK_CLIENT_UNIQUE_ID` | `--unique-id` | — | yes | Stable hardware identifier sent in the lobby `auth` message (fallback when `uidBinaryPath` is unset). |
| `clientVersion` | `FAF_MOCK_CLIENT_CLIENT_VERSION` | `--client-version` | `0.0.0-mock` | no | Client version string sent in the lobby `ask_session` message. |
| `userAgent` | `FAF_MOCK_CLIENT_USER_AGENT` | `--user-agent` | `faf-test-harness` | no | Client identifier string sent in the lobby `ask_session` message. |
| `uidBinaryPath` | `FAF_MOCK_CLIENT_UID_BINARY_PATH` | `--uid-binary-path` | — | no | Path to the FAF `faf-uid` binary. When set, the auth handshake runs `<path> <session>` and sends its output as `unique_id` (the lobby's policy server requires a real RSA-encrypted UID, not a placeholder). When unset, the static `uniqueId` is sent. |
| `iceAdapterBinaryPath` | `FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH` | `--ice-adapter-binary-path` | `faf-ice-adapter.jar` | no | Path to the `faf-ice-adapter` binary; a `.jar` runs via `java -jar`, any other file is executed directly. Relative paths resolve against the working directory. |
| `mockGameBinaryPath` | `FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH` | `--mock-game-binary-path` | `mock-game/build/install/mock-game/bin/mock-game` | no | Path to the `mock-game` binary; a `.jar` runs via `java -jar`, any other file is executed directly. The default is the Gradle `application` plugin install layout (resolved against the working directory), so the harness "just works" from the repo root after `./gradlew :mock-game:installDist`. |
| `iceAdapterRpcPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_RPC_PORT` | `--ice-adapter-rpc-port` | `7236` | no | Local JSON-RPC port exposed by `faf-ice-adapter`. |
| `iceAdapterGpgNetPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_GPG_NET_PORT` | `--ice-adapter-gpg-net-port` | `7237` | no | Local GPGNet port exposed by `faf-ice-adapter`. |
| `iceAdapterLobbyPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_LOBBY_PORT` | `--ice-adapter-lobby-port` | `7238` | no | Local UDP lobby port passed to `faf-ice-adapter` as `--lobby-port`. |
| `logLevel` | `FAF_MOCK_CLIENT_LOG_LEVEL` | `--log-level` | `INFO` | no | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`. |
| `logFile` | `FAF_MOCK_CLIENT_LOG_FILE` | `--log-file` | — | no | Optional JSONL log file path. |
| `playerIdOverride` | `FAF_MOCK_CLIENT_PLAYER_ID_OVERRIDE` | `--player-id-override` | — | no | Player ID override for deterministic local testing; used by the `launch-ice` / `launch-game` / `ice-smoke` diagnostics (a full `run` uses the lobby identity). |
| `playerLogin` | `FAF_MOCK_CLIENT_PLAYER_LOGIN` | `--player-login` | `mock-client` | no | Player login passed to `faf-ice-adapter` as `--login` and to `mock-game` as `--player-login`; used by the `launch-ice` / `launch-game` / `ice-smoke` diagnostics (a full `run` uses the lobby identity). |

¹ The refresh-token file is the **only** credential channel: Hydra rotates the
refresh token on every use and the rotated value is persisted back to this
file, which a literal option could not do. Omitting it produces a picocli
`ParameterException` pointing at the bootstrap procedure in
`documentation/research/lobby-protocol-spec.md` §2 (WBS-2.2.10).

> **Removed (WBS-2.2.10):** `oauthClientSecret`, `oauthUsername`, and
> `oauthPassword` are no longer accepted — the seeded FAF Hydra clients with
> `lobby` scope are *public* (no client secret) and do not enable the
> password-grant or client_credentials grant types. Configs that still set
> these keys fail at load time with a deprecation error pointing at the spec.
> A literal `oauthRefreshToken` option is likewise no longer offered; write
> the bootstrap token to a file instead.

### Auth flow

The mock client uses OAuth2 refresh-token rotation against the seeded
`FAF Classic Client (Python)` public client. The flow is two-phase:

1. **One-time bootstrap** (manual, per refresh-token lifetime ≈ 30 days):
   open the authorization endpoint in a browser, log in as a test user, and
   exchange the resulting authorization code for a refresh token. Persist the
   token to `oauthRefreshTokenFile`. Full procedure in
   `documentation/research/lobby-protocol-spec.md` §2.
2. **Steady-state** (headless, runtime): on startup or when the access token
   nears expiry, POST to the token endpoint with `grant_type=refresh_token`.
   Hydra rotates the refresh token on every use — the loader caller must
   rewrite `oauthRefreshTokenFile` atomically *before* treating the refresh as
   successful.

The `oauthAccessToken` / `oauthTokenFile` fields are auxiliary: they accept the
bootstrap's access-token output directly, which is convenient for one-shot
smoke tests but does not survive an access-token expiry (~1 hour).

### Secrets

The example file (`mock-client.example.json`) contains placeholder values only.
**Do not commit real OAuth refresh tokens or access tokens.** In CI, supply
these via environment variables or CLI flags, never via a checked-in JSON file.

A typical setup:

- Public values (`lobbyWebSocketUrl`, `oauthTokenUrl`, `oauthAuthEndpoint`,
  `oauthRedirectUri`, `oauthScopes`, `oauthClientId`, ports, binary paths) →
  `mock-client.json`, tracked in version control.
- The secret: `oauthRefreshTokenFile` pointing at a gitignored file, with the
  path injected as a `FAF_MOCK_CLIENT_*` env var at runtime (the file itself
  lives in the CI secret store or a local `.secrets/` directory).

Refresh tokens are environment-specific (they encode the Hydra issuer and
client ID), so each environment (`.xyz` / production / future local Tilt
stack) needs its own refresh-token file.

## Example invocations

Two launchers are equivalent: the Gradle `application` plugin's `:run` task
(no build step required), and the install-dist binary built by
`./gradlew :mock-client:installDist` and located at
`mock-client/build/install/mock-client/bin/mock-client`. The first is convenient
during development; the second is what CI and deployments use.

### Discover the available options

```bash
./gradlew :mock-client:run --args="--help"
./gradlew :mock-client:run --args="run --help"
```

Root help lists every global flag and the four subcommands. Per-subcommand
help shows the same flag set (subcommands inherit the root's flags). This is
the source of truth that the field-reference table above mirrors.

### `run` — connect to the lobby and sit idle (config file)

Connects to the lobby, runs the auth handshake, hydrates the welcome state, logs
the authenticated player id, then stays idle (auto-replying `pong` to the lobby's
`ping` heartbeats) until `Ctrl-C` / `SIGTERM` closes the socket cleanly.

```bash
cp mock-client.example.json mock-client.json
# edit mock-client.json with real values
./gradlew :mock-client:run --args="run --config mock-client.json"
```

`run` authenticates via the refresh-token **file** channel (`--oauth-refresh-token-file`
/ `FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE` / `oauthRefreshTokenFile`), since
the token is rotated and persisted back to the file on each use. There is no
literal token option; write the bootstrap token to a file. If the file is
missing or unreadable, `run` exits `70` (`RUNTIME`) before connecting.

Against the live lobby you also need `--uid-binary-path` pointing at the FAF
`faf-uid` binary: the lobby's policy server rejects a placeholder `unique_id`
(the login ends in `{"command":"invalid"}`), so the handshake runs `faf-uid` with
the session to produce a real RSA-encrypted UID. See
[`documentation/operations/harness-runbook.md`](../documentation/operations/harness-runbook.md)
for the ordered setup path (prerequisites, credentials, config, and this
command), or [`documentation/demos/README.md`](../documentation/demos/README.md)
for the sprint-review evidence capture of this exact path.

### Providing the faf-ice-adapter binary

`launch-ice` (and later `run`) needs the upstream `faf-ice-adapter` on disk —
the harness does not download it. The path is set via
`--ice-adapter-binary-path` / `FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH` /
`iceAdapterBinaryPath`, and **defaults to `faf-ice-adapter.jar`** resolved
against the Mock Client's working directory. Two forms are accepted:

- a **`.jar`** — launched as `java -jar` on the same JRE as the Mock Client;
- a **native executable / launcher script** — executed directly.

The path is existence-checked before launch; a missing file fails fast with a
single-line error and exit code `70` — no stack trace.

Obtain the JAR by building it from upstream
[`FAForever/java-ice-adapter`](https://github.com/FAForever/java-ice-adapter)
or downloading a release artifact, then either drop it next to the Mock Client
as `faf-ice-adapter.jar` (the default) or point the config at it. In the Docker
workspace the image is expected to bake it in (`subprocess-orchestration-spec`
§2.2).

### Providing the mock-game binary

`launch-game` (and later `run`) needs the in-repo `mock-game` binary. The path
is set via `--mock-game-binary-path` / `FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH` /
`mockGameBinaryPath`, and **defaults to
`mock-game/build/install/mock-game/bin/mock-game`** — the layout produced by the
Gradle `application` plugin — resolved against the Mock Client's working
directory. The same JAR-vs-native dispatch applies (`.jar` → `java -jar`, any
other file executed directly), and a missing file fails fast with a single-line
error and exit code `70`.

Build the binary from the repo root with `./gradlew :mock-game:installDist`;
the harness then "just works" when invoked from the repo root with the default.
Override the path only when the layout differs (e.g. a Docker image baking the
binary in at a fixed location).

### `launch-ice` — spawn faf-ice-adapter only

Spawns the adapter, runs it for `--duration-seconds` (default `10`), terminates
it, and logs the exit code. The adapter's output appears in the logs tagged
`[ICEAdapter]`. Having no lobby, this diagnostic takes `--id`, `--login`, and
`--game-id` from `playerIdOverride`, `playerLogin`, and `iceAdapterGameId`
(default `0`, meaning no session) rather than from the lobby `welcome` and
`game_launch` a full `run` uses.

```bash
./gradlew :mock-client:run --args="\
  launch-ice \
  --config mock-client.json \
  --ice-adapter-rpc-port 7236 \
  --ice-adapter-gpg-net-port 7237 \
  --ice-adapter-lobby-port 7238 \
  --duration-seconds 30"
```

A missing or invalid `--ice-adapter-binary-path` produces a single-line error
and exits `70` (`RUNTIME`) — no stack trace.

### `launch-game` — spawn mock-game only

Spawns `mock-game`, runs it for `--duration-seconds` (default `10`), terminates
it, and logs the exit code. The game's output appears in the logs tagged
`[MockGame]`. The argv is `subprocess-orchestration-spec` §2.8
(`--gpgnet-port`, `--lobby-port`, `--player-id`, `--player-login`,
`--game-uid`). Having no lobby, this diagnostic takes the identity from
`playerIdOverride`, `playerLogin`, and `iceAdapterGameId` (default `0`, meaning
no session) rather than from the lobby `welcome` and `game_launch` a full `run`
uses.

```bash
./gradlew :mock-client:run --args="\
  launch-game \
  --config mock-client.json \
  --duration-seconds 30"
```

A missing or invalid `--mock-game-binary-path` produces a single-line error and
exits `70` (`RUNTIME`) — no stack trace.

### `ice-smoke` — is a local adapter reachable?

The one command that exercises the harness without a FAF account: no lobby, no
OAuth, and nothing this harness sends leaves loopback. Run it as a precondition
before paying for a full session test, and to tell "the adapter never came up"
apart from "the session logic is wrong".

The adapter itself is less abstemious: on every launch `faf-ice-adapter` 3.3.14
opens a telemetry WebSocket to `ice-telemetry.faforever.com`, which it has no
flag to disable (`json-rpc-spec.md` §8). The verdict does not depend on it — a
refused connection makes the adapter unregister its telemetry debugger and carry
on — but on a network that blackholes rather than refuses, expect the adapter's
boot, and so this check, to be slower than the usual two seconds.

```bash
./gradlew :mock-client:installDist
./gradlew downloadIceAdapter

./mock-client/build/install/mock-client/bin/mock-client ice-smoke \
  --ice-adapter-binary-path="$PWD/faf-ice-adapter.jar"
```

That is the whole invocation — no other flag is required. From the repo root,
where `downloadIceAdapter` puts the jar on the default path, even the binary
flag is optional: `mock-client ice-smoke` on its own passes. The ports default
to `7236` / `7237` / `7238`; pass `--ice-adapter-rpc-port` and friends to run
alongside something already using them.

A pass looks like this (`[MockClient]` = the harness, `[ICEAdapter]` = the real
jar's own output, trimmed here):

```text
[MockClient] Launching ICE adapter: <java> ... --rpc-port 7236 --gpgnet-port 7237 --lobby-port 7238
[MockClient] ice-smoke: connecting to ICE adapter JSON-RPC at 127.0.0.1:7236 (within PT10.97S)
[ICEAdapter] c.f.i.g.GPGNetServer - GPGNetServer started
[ICEAdapter] c.n.jjsonrpc.TcpServer - TCP Server started.
[MockClient] connected to ICE adapter JSON-RPC at 127.0.0.1:7236
[MockClient] ice-smoke: RPC round-trip setLobbyInitMode (within PT2S)
[MockClient] ice-smoke: probing GPGNet endpoint at 127.0.0.1:7237 (within PT2S)
[MockClient] ice-smoke: awaiting adapter's GPGNet connection notice (within PT5S)
[ICEAdapter] c.f.i.g.GPGNetServer - GPGNetClient has connected
[MockClient] ice-smoke: ICE adapter terminated; exit code 143
[MockClient] ice-smoke: PASS - ICE adapter reachable: JSON-RPC 127.0.0.1:7236 answered, GPGNet 127.0.0.1:7237 served the probe
```

The whole run takes about two seconds; every wait is bounded and named.
`--timeout-seconds` (default `20`, max `3600`) caps the checking itself — the
phases from launch to verdict. That default is failure headroom, not the
expected runtime: the check returns the moment it has its verdict. The connect
phase does not get the whole budget — it reserves the nine seconds the three
later phases can need, which is why the transcript above shows it waiting
`PT10.97S` rather than the full `PT20S`. Without that reserve, a slow-starting
adapter would spend the budget on the connect and the phases after it would
fail instantly, reporting a startup problem under the wrong name. Tearing the
adapter down is deliberately *not* inside that cap, because skipping it to
honour a budget would leave a stray adapter to break the next run's port
pre-flight; it is bounded separately by a 2 s SIGTERM→SIGKILL grace. So the
honest worst case for the whole command is the budget plus about four seconds,
and only against an adapter that ignores SIGTERM (measured: `3.7 s` total for
`--timeout-seconds=2` against one that does). The adapter may log a lost-connection line as the probe disconnects
(`Error while communicating with FA (input), assuming shutdown` /
`GPGNet connection lost`); that is the adapter noticing the probe going away, and
it is expected on a passing run.

A failure exits `70` and names the phase that decided it, so a CI log explains
itself without a rerun:

```text
[MockClient] ice-smoke: FAIL [GPGNET_UNREACHABLE] GPGNet probe: could not connect to 127.0.0.1:7237 within PT2S (Connection refused)
```

| Verdict | Meaning |
|---|---|
| `PORTS_IN_USE` | A configured port was already taken, so anything answering would not be the adapter this run started. Stop the other adapter, or pass different ports. |
| `LAUNCH_FAILED` | The adapter binary is missing or could not be started. |
| `ADAPTER_EXITED` | The adapter exited mid-check; its exit code is named when known. |
| `RPC_UNREACHABLE` | Nothing accepted a JSON-RPC connection within the budget. |
| `RPC_SILENT` | The socket opened but the adapter never answered a request on it. |
| `GPGNET_UNREACHABLE` | The GPGNet port refused the probe. |
| `GPGNET_UNCONFIRMED` | The GPGNet port accepted, but the adapter never announced it over RPC — the two halves are not wired together. |
| `INTERRUPTED` | The thread running the check was interrupted. Not what `Ctrl-C` does: SIGINT ends the JVM at exit `130` with no verdict line at all (the adapter is still reaped, by the subprocess shutdown hook). This verdict is for programmatic callers of `IceReachabilityCheck`. |

What a pass proves: the binary launches, its JSON-RPC endpoint parses and answers
a request, its GPGNet endpoint accepts a client, and the adapter's two halves are
wired to each other. What it does not prove: ICE negotiation, lobby connectivity,
or anything about `mock-game` — those need a full session (`run`) or the
lifecycle tests.

### Environment variables only

```bash
export FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL=wss://ws.faforever.xyz
export FAF_MOCK_CLIENT_OAUTH_TOKEN_URL=https://hydra.faforever.xyz/oauth2/token
export FAF_MOCK_CLIENT_OAUTH_AUTH_ENDPOINT=https://hydra.faforever.xyz/oauth2/auth
export FAF_MOCK_CLIENT_OAUTH_REDIRECT_URI=http://127.0.0.1
export FAF_MOCK_CLIENT_OAUTH_SCOPES="openid offline lobby"
export FAF_MOCK_CLIENT_OAUTH_CLIENT_ID=95ecec08-29c1-4c48-ae0a-b000ff349cb8
export FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE=./.secrets/refresh_token.txt
export FAF_MOCK_CLIENT_UNIQUE_ID=00000000-0000-0000-0000-000000000000
export FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH=/usr/local/bin/faf-ice-adapter
export FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH=./mock-game/build/install/mock-game/bin/mock-game

./gradlew :mock-client:run --args="run"
```

### CLI flags only

```bash
./gradlew :mock-client:run --args="\
  run \
  --lobby-websocket-url wss://ws.faforever.xyz \
  --oauth-token-url https://hydra.faforever.xyz/oauth2/token \
  --oauth-auth-endpoint https://hydra.faforever.xyz/oauth2/auth \
  --oauth-redirect-uri http://127.0.0.1 \
  --oauth-scopes 'openid offline lobby' \
  --oauth-client-id 95ecec08-29c1-4c48-ae0a-b000ff349cb8 \
  --oauth-refresh-token-file ./.secrets/refresh_token.txt \
  --unique-id 00000000-0000-0000-0000-000000000000 \
  --ice-adapter-binary-path /usr/local/bin/faf-ice-adapter \
  --mock-game-binary-path ./mock-game/build/install/mock-game/bin/mock-game"
```

### Layered (typical CI shape)

```bash
# config file holds public values
./gradlew :mock-client:run --args="\
  run \
  --config mock-client.json \
  --log-level DEBUG"
# env adds the secret's location:
#   FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE
# the --log-level flag overrides whatever the file said
```

### Multiple clients on one box

To simulate 2–4 players locally, give each instance its own ports, player ID,
log file, and instance name. Public values come from the shared config file,
per-client values come from CLI flags and the `INSTANCE_NAME` environment
variable:

```bash
INSTANCE_NAME=peer-a ./gradlew :mock-client:run --args="\
  run \
  --config mock-client.json \
  --player-id-override 1 \
  --ice-adapter-rpc-port 7236 \
  --ice-adapter-gpg-net-port 7237 \
  --log-file logs/client-1.jsonl" &

INSTANCE_NAME=peer-b ./gradlew :mock-client:run --args="\
  run \
  --config mock-client.json \
  --player-id-override 2 \
  --ice-adapter-rpc-port 7246 \
  --ice-adapter-gpg-net-port 7247 \
  --log-file logs/client-2.jsonl" &
```

`INSTANCE_NAME` is the multi-instance convention. It labels every record with
the instance that emitted it, and it gives each instance its own default log
file, `logs/<component>-<instance>.jsonl`. Leaving it unset is the normal
single-instance case and changes nothing, neither the default path nor the
record shape.

That default is what separates the subprocesses. `--log-file` is a CLI flag, so
it becomes a system property and does not cross a process boundary; the
launchers deliberately pass no `LOG_FILE` to their children (see
`MockGameLauncher`). Without a per-instance default, every `mock-game` would
fall back to `logs/mockgame.jsonl` and both instances' games would contend on
one rolling file. With `INSTANCE_NAME` set, each writes
`logs/mockgame-<instance>.jsonl` on its own, and its records carry the label
too. Prefer this to exporting `LOG_FILE`, which would put a client and its own
child in one file.

Supply `INSTANCE_NAME` as an environment variable rather than
`-DINSTANCE_NAME`, for the same inheritance reason: the value reaches the
subprocesses this client launches, so `mock-game` reads it at its own startup
and labels its own records with it. `faf-ice-adapter` is a third-party binary
that knows nothing about the variable, so its output is labelled only in the
records this client captures from its stdout and stderr, not in any log the
adapter writes itself.

## Harness log contract

An automated harness observes a running client from the outside, through its
log records alone (WBS-3.1.6.2). There is no health port and no readiness
message — see the note at the end of this section. The formats below are a
documented interface consumed by the N-client spawner (WBS 4.2.2) and the
fault-injection cards (Phase 5). **Changing any of them is a breaking change**
for those cards, and each is pinned by a test: `HarnessLogContractTest` and
`IceEventLoggerTest` parse real JSONL records, and `WelcomeStateSyncTest` pins
the `session ready` fields.

Read the JSONL file rather than the console: every record is one line of JSON
with a millisecond `timestamp`, a `component`, and an `instance` when one is
named.

### Lifecycle

One record on entry to each FSM state. `CONNECTING` is emitted once when the
lifecycle is constructed, because the initial state fires no entry hook. A
transition that stays in the same state, such as losing the lobby while
`PLAYING`, does not repeat the line.

| Line | Meaning |
|---|---|
| `state entry: <STATE>` | The client entered `<STATE>`, one of `CONNECTING`, `IDLE`, `STARTING_GAME`, `HOSTING`, `JOINING`, `PLAYING`, `TERMINATED`. |

The line precedes that state's side effects, so `state entry: TERMINATED`
appears before teardown output.

### Identity

| Line | Meaning |
|---|---|
| `session ready: id=<id> login=<login>` | The lobby assigned this client its player ID and login in the `welcome` frame. |
| `game launch: uid=<uid> mod=<mod> name=<name>` | This client entered the game with lobby-assigned `<uid>`, and its adapter and game are up. The uid is what a second instance needs as its join target. Emitted by host and joiner alike, and only on a launch that succeeded, so a failed launch reports `state entry: TERMINATED` with no uid. `name` is free text and always last. |

Note the ordering: `game launch` is emitted **before** `state entry:
STARTING_GAME`, because the launch happens in the FSM transition action and the
state line is emitted when the target state is entered. A harness that waits
for `STARTING_GAME` and only then begins scanning will miss the uid. Wait for
the `game launch` line itself.

### Connection state

Three distinct signals. They are **not** interchangeable, and only the peer
ones move during ICE negotiation:

| Line | Meaning |
|---|---|
| `gpgnet link: state=<state>` | The local mock game connected to or disconnected from this client's adapter over GPGNet. Not a peer signal. |
| `peer ice: local=<id> remote=<id> state=<state>` | ICE connection state for one peer. These are the transitions delayed-negotiation tests measure. |
| `peer connected: local=<id> remote=<id> connected=<bool>` | The adapter's verdict that a peer is reachable. The definitive peer-established signal. |

`<state>` is the adapter's own `IceState` vocabulary, not the WebRTC IDL set
the upstream README implies. Verified against the shipped jar (3.3.14), the
values a harness can actually observe are:

```text
new  gathering  awaitingCandidates  checking  connected  disconnected
```

`gathering` and `awaitingCandidates` are where a delayed-negotiation fault
parks, so match on them rather than waiting for a terminal state. Do not match
on `failed` or `closed`, which the adapter never emits, or on `completed`,
which the enum defines but no code path sets. Treat `connected`, or
`peer connected: … connected=true`, as peer ready.

Player IDs are declared 64-bit in the adapter's `RPCService` signatures and are
parsed as such, though the values it emits today are widened from `int`.

A malformed notification is logged at WARN with the prefix
`dropping malformed <method>` and produces no contract line.

### Peer traffic

One line per *sending* peer, at most once a second, emitted by **mock-game** (`component=MockGame`)
and captured into this stream by the client's subprocess logger. It is the only
evidence that the ICE path is carrying game traffic, and it is what the two-peer
exchange test (WBS-4.3.2) asserts on:

| Line | Meaning |
|---|---|
| `player <receiver> peer traffic from player <sender>: <n> datagrams, highest sequence <seq>, gaps <g>` | The game belonging to `<receiver>` has decoded `<n>` datagrams sent by `<sender>`, whose highest sequence number so far is `<seq>`, with `<g>` forward gaps. |

Both ids are on the line, so one record proves one direction without inferring
who logged it — which is what keeps it usable past two peers. It is emitted only
when that sender's count has moved since the last sample, so a stalled stream
goes quiet rather than repeating, and a final line is logged synchronously at
teardown.

Treat the counts as monotone evidence, never as a measurement: everything a game
sends before ICE completes is dropped inside the adapter
(`PeerIceModule.sendViaIce` is guarded by `connected`), so a stream legitimately
starts mid-sequence and with gaps. Assert "at least N, still advancing", which is
what two consecutive lines with a rising `highest sequence` show.

> **Why logs and not a health port.** The lobby protocol has no readiness
> channel to be faithful to: faf-server's `command_match_ready` is an
> unimplemented stub, and the only liveness mechanism is ping/pong. A harness
> already has two capture channels, this JSONL file and the spawner's own
> stdout capture, so a health port would have no consumer today.

## Failure mode

Running with nothing configured produces a usage block followed by a single
error listing every missing required option:

```text
Missing required options: '--lobby-websocket-url=<lobbyWebSocketUrl>',
'--oauth-token-url=<oauthTokenUrl>', '--oauth-auth-endpoint=<oauthAuthEndpoint>',
'--oauth-redirect-uri=<oauthRedirectUri>', '--oauth-scopes=<oauthScopes>',
'--oauth-client-id=<oauthClientId>', '--unique-id=<uniqueId>'

Usage: mock-client [-hV] [--config=<configFile>] ...
       (full picocli usage block)
```

The JVM exits with status `2` so CI can distinguish config errors from runtime
failures.