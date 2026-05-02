# Mock Client

Headless CLI stand-in for the FAF desktop client. Connects to the lobby server,
launches `faf-ice-adapter` and `mock-game` as subprocesses, and proxies GPGNet
traffic between them and the lobby. Used for end-to-end integration tests that
do not require a real game install or a human at the keyboard.

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
| `oauthTokenUrl` | `FAF_MOCK_CLIENT_OAUTH_TOKEN_URL` | `--oauth-token-url` | — | yes | OAuth2 token endpoint used to acquire lobby access tokens. |
| `oauthClientId` | `FAF_MOCK_CLIENT_OAUTH_CLIENT_ID` | `--oauth-client-id` | — | yes | OAuth2 client identifier. |
| `oauthClientSecret` | `FAF_MOCK_CLIENT_OAUTH_CLIENT_SECRET` | `--oauth-client-secret` | — | no¹ | OAuth2 client secret. |
| `oauthUsername` | `FAF_MOCK_CLIENT_OAUTH_USERNAME` | `--oauth-username` | — | no¹ | OAuth username (local/test environments). |
| `oauthPassword` | `FAF_MOCK_CLIENT_OAUTH_PASSWORD` | `--oauth-password` | — | no¹ | OAuth password (local/test environments). |
| `oauthAccessToken` | `FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN` | `--oauth-access-token` | — | no¹ | Pre-obtained JWT bearer token. |
| `oauthTokenFile` | `FAF_MOCK_CLIENT_OAUTH_TOKEN_FILE` | `--oauth-token-file` | — | no¹ | Path to a file containing a pre-obtained JWT. |
| `uniqueId` | `FAF_MOCK_CLIENT_UNIQUE_ID` | `--unique-id` | — | yes | Stable hardware identifier sent in the lobby `auth` message. |
| `iceAdapterBinaryPath` | `FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH` | `--ice-adapter-binary-path` | — | yes | Path to the `faf-ice-adapter` executable. |
| `mockGameBinaryPath` | `FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH` | `--mock-game-binary-path` | — | yes | Path to the `mock-game` executable. |
| `iceAdapterRpcPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_RPC_PORT` | `--ice-adapter-rpc-port` | `7236` | no | Local JSON-RPC port exposed by `faf-ice-adapter`. |
| `iceAdapterGpgNetPort` | `FAF_MOCK_CLIENT_ICE_ADAPTER_GPG_NET_PORT` | `--ice-adapter-gpg-net-port` | `7237` | no | Local GPGNet port exposed by `faf-ice-adapter`. |
| `logLevel` | `FAF_MOCK_CLIENT_LOG_LEVEL` | `--log-level` | `INFO` | no | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`. |
| `logFile` | `FAF_MOCK_CLIENT_LOG_FILE` | `--log-file` | — | no | Optional JSONL log file path. |
| `playerIdOverride` | `FAF_MOCK_CLIENT_PLAYER_ID_OVERRIDE` | `--player-id-override` | — | no | Player ID override for deterministic local testing. |

¹ Each individual `oauth*` credential field is optional, but the loader requires
either a token (`oauthAccessToken` or `oauthTokenFile`) **or** the password-grant
trio (`oauthUsername` + `oauthPassword` + `oauthClientSecret`). Failing to supply
at least one channel produces a picocli `ParameterException` naming both
options.

### Secrets

The example file (`mock-client.example.json`) contains placeholder values only.
**Do not commit real OAuth credentials, JWTs, or client secrets.** In CI, supply
these via environment variables or CLI flags, never via a checked-in JSON file.

A typical setup:

- Public values (`lobbyWebSocketUrl`, `oauthTokenUrl`, `oauthClientId`, ports,
  binary paths) → `mock-client.json`, tracked in version control.
- Secrets (`oauthClientSecret`, `oauthAccessToken`, `oauthPassword`) → CI
  secret store, injected as `FAF_MOCK_CLIENT_*` env vars at runtime.

## Example invocations

### Discover the available options

```bash
./gradlew :mock-client:run --args="--help"
```

Prints every flag, its description, default value, and whether it is required.
This is the source of truth that the field-reference table above mirrors.

### Config file only

```bash
cp mock-client.example.json mock-client.json
# edit mock-client.json with real values
./gradlew :mock-client:run --args="--config mock-client.json"
```

### Environment variables only

```bash
export FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL=ws://localhost/ws
export FAF_MOCK_CLIENT_OAUTH_TOKEN_URL=http://localhost:4444/oauth2/token
export FAF_MOCK_CLIENT_OAUTH_CLIENT_ID=faf-client
export FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN=eyJhbGciOi...
export FAF_MOCK_CLIENT_UNIQUE_ID=00000000-0000-0000-0000-000000000000
export FAF_MOCK_CLIENT_ICE_ADAPTER_BINARY_PATH=/usr/local/bin/faf-ice-adapter
export FAF_MOCK_CLIENT_MOCK_GAME_BINARY_PATH=./mock-game/build/install/mock-game/bin/mock-game

./gradlew :mock-client:run
```

### CLI flags only

```bash
./gradlew :mock-client:run --args="\
  --lobby-websocket-url ws://localhost/ws \
  --oauth-token-url http://localhost:4444/oauth2/token \
  --oauth-client-id faf-client \
  --oauth-token-file ./.secrets/access-token.jwt \
  --unique-id 00000000-0000-0000-0000-000000000000 \
  --ice-adapter-binary-path /usr/local/bin/faf-ice-adapter \
  --mock-game-binary-path ./mock-game/build/install/mock-game/bin/mock-game"
```

### Layered (typical CI shape)

```bash
# config file holds public values
./gradlew :mock-client:run --args="\
  --config mock-client.json \
  --log-level DEBUG"
# env adds secrets:
#   FAF_MOCK_CLIENT_OAUTH_CLIENT_SECRET, FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN
# the --log-level flag overrides whatever the file said
```

### Multiple clients on one box

To simulate 2–4 players locally, give each instance its own ports, player ID,
and log file. Public values come from the shared config file, per-client values
come from CLI flags:

```bash
./gradlew :mock-client:run --args="\
  --config mock-client.json \
  --player-id-override 1 \
  --ice-adapter-rpc-port 7236 \
  --ice-adapter-gpg-net-port 7237 \
  --log-file logs/client-1.jsonl" &

./gradlew :mock-client:run --args="\
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
'--oauth-token-url=<oauthTokenUrl>', '--oauth-client-id=<oauthClientId>',
'--unique-id=<uniqueId>', '--ice-adapter-binary-path=<iceAdapterBinaryPath>',
'--mock-game-binary-path=<mockGameBinaryPath>'

Usage: mock-client [-hV] [--config=<configFile>] ...
       (full picocli usage block)
```

The JVM exits with status `2` so CI can distinguish config errors from runtime
failures.