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
| `run`         | Run a full mock client session: authenticate, queue, play, teardown.               |
| `launch-ice`  | Spawn `faf-ice-adapter` only and forward its output through the harness logger.    |
| `launch-game` | Spawn `mock-game` only and forward its output through the harness logger.          |
| `ice-smoke`   | ICE-adapter connectivity smoke test: bring up the adapter, verify GPGNet handshake.|

`launch-ice` (WBS-3.1.2.2) and `launch-game` (WBS-3.1.2.3) are implemented:
each spawns its respective binary, runs it for `--duration-seconds`, terminates
it, and logs the exit code. `run` and `ice-smoke` are still CLI scaffolding —
they validate config, apply logging, log a TODO line, and exit with code `64`
(`NOT_IMPLEMENTED`). Real logic for each ships in sibling tracks.

Invocation shape:

```text
mock-client [global flags] <subcommand> [subcommand flags]
```

Global flags — `--config`, `--log-level`, `--help`, `--version`, plus all 20
config flags — are declared on the root and apply to every subcommand. Each
subcommand also accepts its own `--help`. `launch-ice` and `launch-game`
additionally take a subcommand-local `--duration-seconds` flag.

## Exit codes

| Code | Constant          | When                                                                             |
|------|-------------------|----------------------------------------------------------------------------------|
| `0`  | `OK`              | Successful run; `--help` and `--version`.                                        |
| `2`  | `USAGE`           | Bad invocation: invalid args, missing required options, unknown subcommand, no subcommand, unreadable config file, malformed JSON, bad URI, bad port. |
| `64` | `NOT_IMPLEMENTED` | Subcommand acknowledged but its real logic has not shipped yet (`run`, `ice-smoke` stubs). |
| `70` | `RUNTIME`         | A runtime failure after a subcommand started — e.g. `launch-ice` / `launch-game` could not find/start its binary, or the child exited before its run window. |

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
| `oauthRefreshToken` | `FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN` | `--oauth-refresh-token` | — | no¹ | Long-lived OAuth refresh token (sensitive — rotated on each use). |
| `oauthRefreshTokenFile` | `FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE` | `--oauth-refresh-token-file` | — | no¹ | Path to the refresh-token file; rewritten atomically on rotation. |
| `oauthAccessToken` | `FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN` | `--oauth-access-token` | — | no¹ | Pre-obtained JWT bearer token (auxiliary/bootstrap output). |
| `oauthTokenFile` | `FAF_MOCK_CLIENT_OAUTH_TOKEN_FILE` | `--oauth-token-file` | — | no¹ | Path to a file containing a pre-obtained JWT (auxiliary/bootstrap output). |
| `uniqueId` | `FAF_MOCK_CLIENT_UNIQUE_ID` | `--unique-id` | — | yes | Stable hardware identifier sent in the lobby `auth` message. |
| `iceAdapterBinaryPath` | `FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH` | `--ice-adapter-binary-path` | `faf-ice-adapter.jar` | no | Path to the `faf-ice-adapter` binary; a `.jar` runs via `java -jar`, any other file is executed directly. Relative paths resolve against the working directory. |
| `mockGameBinaryPath` | `FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH` | `--mock-game-binary-path` | `mock-game/build/install/mock-game/bin/mock-game` | no | Path to the `mock-game` binary; a `.jar` runs via `java -jar`, any other file is executed directly. The default is the Gradle `application` plugin install layout (resolved against the working directory), so the harness "just works" from the repo root after `./gradlew :mock-game:installDist`. |
| `iceAdapterRpcPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_RPC_PORT` | `--ice-adapter-rpc-port` | `7236` | no | Local JSON-RPC port exposed by `faf-ice-adapter`. |
| `iceAdapterGpgNetPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_GPG_NET_PORT` | `--ice-adapter-gpg-net-port` | `7237` | no | Local GPGNet port exposed by `faf-ice-adapter`. |
| `iceAdapterLobbyPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_LOBBY_PORT` | `--ice-adapter-lobby-port` | `7238` | no | Local UDP lobby port passed to `faf-ice-adapter` as `--lobby-port`. |
| `logLevel` | `FAF_MOCK_CLIENT_LOG_LEVEL` | `--log-level` | `INFO` | no | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`. |
| `logFile` | `FAF_MOCK_CLIENT_LOG_FILE` | `--log-file` | — | no | Optional JSONL log file path. |
| `playerIdOverride` | `FAF_MOCK_CLIENT_PLAYER_ID_OVERRIDE` | `--player-id-override` | — | no | Player ID override for deterministic local testing. |
| `playerLogin` | `FAF_MOCK_CLIENT_PLAYER_LOGIN` | `--player-login` | `mock-client` | no | Player login passed to `faf-ice-adapter` as `--login`; used by the `launch-ice` / `ice-smoke` diagnostics (a full `run` uses the lobby identity). |

¹ Each individual OAuth-credential field is optional, but the loader requires
at least one credential channel: a refresh token (`oauthRefreshToken` or
`oauthRefreshTokenFile`) **or** a pre-obtained access token (`oauthAccessToken`
or `oauthTokenFile`). Failing to supply at least one channel produces a picocli
`ParameterException` pointing at the bootstrap procedure in
`documentation/research/lobby-protocol-spec.md` §2 (WBS-2.2.10).

> **Removed (WBS-2.2.10):** `oauthClientSecret`, `oauthUsername`, and
> `oauthPassword` are no longer accepted. The seeded FAF Hydra clients with
> `lobby` scope are *public* (no client secret) and do not enable the
> password-grant or client_credentials grant types. Configs that still set
> these keys fail at load time with a deprecation error pointing at the spec.

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
- Secrets (`oauthRefreshToken`, `oauthAccessToken`) or
  `oauthRefreshTokenFile` pointing at a gitignored file → CI secret store,
  injected as `FAF_MOCK_CLIENT_*` env vars at runtime.

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

### `run` — full mock client session (config file)

```bash
cp mock-client.example.json mock-client.json
# edit mock-client.json with real values
./gradlew :mock-client:run --args="run --config mock-client.json"
```

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
`[ICEAdapter]`.

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
`[MockGame]`. The argv is the config-derivable subset of
`subprocess-orchestration-spec` §2.8 (`--gpgnet-port`, `--lobby-port`,
`--player-id`, `--player-login`); the `game_launch`-derived flags (uid, mod,
map, faction, team) are FSM scope and arrive with orchestration.

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
export FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL=wss://lobby.faforever.xyz
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
  --lobby-websocket-url wss://lobby.faforever.xyz \
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
# env adds secrets:
#   FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN (or _FILE)
# the --log-level flag overrides whatever the file said
```

### Multiple clients on one box

To simulate 2–4 players locally, give each instance its own ports, player ID,
and log file. Public values come from the shared config file, per-client values
come from CLI flags:

```bash
./gradlew :mock-client:run --args="\
  run \
  --config mock-client.json \
  --player-id-override 1 \
  --ice-adapter-rpc-port 7236 \
  --ice-adapter-gpg-net-port 7237 \
  --log-file logs/client-1.jsonl" &

./gradlew :mock-client:run --args="\
  run \
  --config mock-client.json \
  --player-id-override 2 \
  --ice-adapter-rpc-port 7246 \
  --ice-adapter-gpg-net-port 7247 \
  --log-file logs/client-2.jsonl" &
```

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