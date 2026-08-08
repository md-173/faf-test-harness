# Mock Client

Headless CLI stand-in for the FAF desktop client. Connects to the lobby server,
launches `faf-ice-adapter` and `mock-game` as subprocesses, and proxies GPGNet
traffic between them and the lobby. Used for end-to-end integration tests that
do not require a real game install or a human at the keyboard.

## Subcommands

Mock Client is a Picocli command tree: a root `mock-client` command plus four
subcommands that dispatch to the matching component.

| Subcommand    | Purpose                                                                            |
|---------------|------------------------------------------------------------------------------------|
| `run`         | Connect to the lobby, authenticate, and sit idle until interrupted.                 |
| `launch-ice`  | Spawn `faf-ice-adapter` only and forward its output through the harness logger.    |
| `launch-game` | Spawn `mock-game` only and forward its output through the harness logger.          |
| `ice-smoke`   | ICE-adapter connectivity smoke test: bring up the adapter, verify GPGNet handshake.|

`run` (WBS-3.1.1.4) connects to the lobby, runs the auth handshake
(`ask_session → session → auth → welcome`), hydrates the welcome state, logs the
authenticated player id, and then sits idle — the transport auto-replies `pong`
to the lobby's `ping` heartbeats. `Ctrl-C` / `SIGTERM` closes the WebSocket
cleanly (the process exit code then follows the signal: 130 for SIGINT, 143 for
SIGTERM). `launch-ice` (WBS-3.1.2.2) and `launch-game` (WBS-3.1.2.3) each spawn
their respective binary, run it for `--duration-seconds`, terminate it, and log
the exit code. `ice-smoke` is still CLI scaffolding — it validates config,
applies logging, logs a TODO line, and exits with code `64` (`NOT_IMPLEMENTED`).

Invocation shape:

```text
mock-client [global flags] <subcommand> [subcommand flags]
```

Global flags — `--config`, `--log-level`, `--help`, `--version`, plus all 23
config flags — are declared on the root and apply to every subcommand. Each
subcommand also accepts its own `--help`. `launch-ice` and `launch-game`
additionally take a subcommand-local `--duration-seconds` flag.

## Exit codes

| Code | Constant          | When                                                                             |
|------|-------------------|----------------------------------------------------------------------------------|
| `0`  | `OK`              | Successful run; `--help` and `--version`.                                        |
| `2`  | `USAGE`           | Bad invocation: invalid args, missing required options, unknown subcommand, no subcommand, unreadable config file, malformed JSON, bad URI, bad port. |
| `64` | `NOT_IMPLEMENTED` | Subcommand acknowledged but its real logic has not shipped yet (`ice-smoke` stub). |
| `70` | `RUNTIME`         | A runtime failure after a subcommand started — e.g. `run` had no usable refresh-token file or the lobby session failed, or `launch-ice` / `launch-game` could not find/start its binary or the child exited before its run window. |

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
`--help` wins.

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
[`documentation/demos/README.md`](../documentation/demos/README.md) for the full
recipe (endpoint, token bootstrap, and obtaining the binary).

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

### `ice-smoke` — connectivity sanity check

```bash
./mock-client/build/install/mock-client/bin/mock-client \
  ice-smoke \
  --config mock-client.json
```

### Environment variables only

```bash
export FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL=wss://ws.faforever.xyz
export FAF_MOCK_CLIENT_OAUTH_TOKEN_URL=https://hydra.faforever.xyz/oauth2/token
export FAF_MOCK_CLIENT_OAUTH_AUTH_ENDPOINT=https://hydra.faforever.xyz/oauth2/auth
export FAF_MOCK_CLIENT_OAUTH_REDIRECT_URI=http://127.0.0.1
export FAF_MOCK_CLIENT_OAUTH_SCOPES="openid offline lobby"
export FAF_MOCK_CLIENT_OAUTH_CLIENT_ID=95ecec08-29c1-4c48-ae0a-b000ff349cb8
export FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE=./.secrets/refresh-token
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
  --oauth-refresh-token-file ./.secrets/refresh-token \
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

`INSTANCE_NAME` and `--log-file` are the multi-instance convention: the label
identifies the instance inside each record, the file separates the streams.
Leaving it unset is the normal single-instance case and changes nothing about
the output.

Use the environment variable rather than `-DINSTANCE_NAME`. The value is
inherited by the subprocesses this client launches, so `mock-game` reads it at
its own startup and self-labels its own log file with it; a system property
would not cross the process boundary. `faf-ice-adapter` is a third-party
binary that knows nothing about the variable, so its output is labelled only
in the records this client captures from its stdout and stderr, not in any log
the adapter writes itself.

## Harness log contract

An automated harness observes a running client from the outside, through its
log records alone (WBS-3.1.6.2). There is no health port and no readiness
message — see the note at the end of this section. The formats below are a
documented interface consumed by the N-client spawner (WBS 4.2.2) and the
fault-injection cards (Phase 5). **Changing any of them is a breaking change**
for those cards, and each is pinned by a test that parses real JSONL records
(`HarnessLogContractTest`, `IceEventLoggerTest`).

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
| `game launch: uid=<uid> mod=<mod> name=<name>` | This client entered the game with lobby-assigned `<uid>`. The uid is what a second instance needs as its join target. Emitted by host and joiner alike. `name` is free text and always last. |

### Connection state

Three distinct signals. They are **not** interchangeable, and only the peer
ones move during ICE negotiation:

| Line | Meaning |
|---|---|
| `gpgnet link: state=<state>` | The local mock game connected to or disconnected from this client's adapter over GPGNet. Not a peer signal. |
| `peer ice: local=<id> remote=<id> state=<state>` | ICE connection state for one peer, mirroring `RTCPeerConnection.iceConnectionState` (`new`, `checking`, `connected`, `completed`, `failed`, `disconnected`, `closed`). These are the transitions delayed-negotiation tests measure. |
| `peer connected: local=<id> remote=<id> connected=<bool>` | The adapter's verdict that a peer is reachable. The definitive peer-established signal. |

Player IDs are 64-bit, matching the adapter's `RPCService` signatures.

A malformed notification is logged at WARN with the prefix
`dropping malformed <method>` and produces no contract line.

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