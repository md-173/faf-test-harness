# Mock Client

Headless CLI stand-in for the FAF desktop client. Connects to the lobby server,
launches `faf-ice-adapter` and `mock-game` as subprocesses, and proxies GPGNet
traffic between them and the lobby. Used for end-to-end integration tests that
do not require a real game install or a human at the keyboard.

## Subcommands

Mock Client is a Picocli command tree: a root `mock-client` command plus five
subcommands that dispatch to the matching component.

| Subcommand    | Purpose                                                                            | Needs a FAF account |
|---------------|------------------------------------------------------------------------------------|---|
| `run`         | Connect to the lobby, authenticate, and sit idle until interrupted.                 | yes |
| `launch-ice`  | Spawn `faf-ice-adapter`, attach a JSON-RPC peer, and hold it up for a window.       | no |
| `launch-game` | Spawn `mock-game` only and forward its output through the harness logger.          | no |
| `ice-smoke`   | Bring up the adapter, verify its JSON-RPC and GPGNet endpoints are serving, tear it down. | no |
| `session`     | Run a host and joiners through the live lobby to a full peer mesh with two-way game traffic, then tear everything down. | yes, one per peer |

`run` (WBS-3.1.1.4) connects to the lobby, runs the auth handshake
(`ask_session → session → auth → welcome`), hydrates the welcome state, logs the
authenticated player id, and then sits idle — the transport auto-replies `pong`
to the lobby's `ping` heartbeats. `Ctrl-C` / `SIGTERM` closes the WebSocket
cleanly (the process exit code then follows the signal: 130 for SIGINT, 143 for
SIGTERM). `launch-ice` (WBS-3.1.2.2) and `launch-game` (WBS-3.1.2.3) each spawn
their respective binary, run it for `--duration-seconds`, terminate it, and log
the exit code. `launch-ice` also attaches a JSON-RPC peer to the adapter and
holds it open for the window (WBS-3.1.6.3), which is what lets a separately
launched `launch-game` complete a real GPGNet handshake against it — the adapter
will not serve a game until a peer exists. `ice-smoke` (WBS-3.1.4.3) is the reachability gate: it spawns the
adapter, connects to its JSON-RPC port, sends one request, connects to its GPGNet
port and waits for the adapter to announce that connection back over RPC, then
tears everything down. Exit `0` means reachable; any other exit names the phase
that failed. A healthy run takes about two seconds.

None of the three diagnostics needs credentials of any kind. Each validates only
the fields it actually reads — `ice-smoke` and `launch-ice` the adapter options,
`launch-game` the mock-game options — so `mock-client launch-ice
--ice-adapter-binary-path=…` runs with no other flags at all (WBS-3.1.5.2-fix,
#308). They used to demand syntactically valid placeholders for the lobby and
OAuth options, which is what the runbook's
`--oauth-refresh-token-file=dummy-unused-by-launch-ice` was.

`session` (WBS-4.2.1) runs a multi-peer session and passes or fails on its own:
one host and `--peers - 1` joiners (default 2 peers; the flag accepts up to 26),
each with its own account, adapter and game, join one game through the live lobby,
and the command exits `0` once every adapter reports every other peer connected
and every game has received every other game's datagrams with an advancing
sequence, so an adapter that connects but does not forward game packets fails the
run (stage `traffic`). That evidence is each game's INFO progress line, so
`session` needs `--log-level` INFO or finer. Runs have been verified at 2 to 4
peers. The 420 s session deadline does not yet grow with `--peers`, and joiners
start one at a time, so a larger session can run out of it part-way through, after
the peers started so far have logged in (and, on refresh-token files, rotated
their tokens). The ceiling step in #87 will measure that limit and size the
deadline. Each peer needs its own account; see the credentials paragraph below. A
failed checkpoint logs `session: FAIL <peer>: <stage>: <detail>`. The clients
share one JVM, so anything that kills it ends every peer; their adapters and games
are separate processes, and any still running after teardown are killed and fail
the run. The session sets each peer's credential, adapter ports, launch delay and
host or join intent, so the root `--oauth-refresh-token-file` and
`--oauth-access-token-file` are ignored entirely, and the other options it sets
are not used, though they are still validated. Missing adapter, game or `faf-uid`
binaries are refused before any login. Evidence lands in the working directory:
`logs/mockclient.jsonl` (or `--log-file`) holds every client line with the
captured adapter and game output, each tagged with the peer's `instance` label A,
B, ..., and each game also writes `logs/mockgame-<label>.jsonl`. Do not set
`INSTANCE_NAME`; `session` refuses it.

`session` takes one credential file per peer, host first, on one of two channels.
`--peer-refresh-token-file` takes refresh-token files, which the session exchanges
at Hydra and rewrites in place on every run, so point it at the real files, never
copies or a process substitution. `--peer-access-token-file` takes pre-signed
access tokens, the channel `--oauth-access-token-file` uses (WBS-3.1.6.4): each is
sent as-is, never renewed or rewritten, and needs no `--oauth-token-url` or
`--oauth-client-id`. Nothing in the harness checks a token's expiry, so an expired
one fails at the `welcome` stage with the lobby's own rejection; see
[`harness-runbook.md`
§3](../documentation/operations/harness-runbook.md#the-other-credential-channel-a-pre-signed-access-token)
for what a token must carry and what a rejection looks like. Either flag can be
repeated or given comma-separated paths. From the environment they are
`FAF_MOCK_CLIENT_PEER_REFRESH_TOKEN_FILE` and
`FAF_MOCK_CLIENT_PEER_ACCESS_TOKEN_FILE`, and in a `--config` file
`peerRefreshTokenFiles` and `peerAccessTokenFiles`, each one comma-separated
string rather than a JSON array. If both channels are configured, the one at the
higher layer wins (command line, then environment, then config file), and lists
never merge across layers; both at the same layer is refused. Files beyond
`--peers` are unused. A shared file, an unreadable or empty one, a refresh-token
path that is not a regular file, two access tokens for one account (read from the
token's `sub` claim), or fewer files than peers is refused before any login; two
refresh tokens for one account are caught at the joiner's `welcome` stage. The run
logs which list it used and where it came from, as `session: credentials from
<flag> (<layer>)`.

Invocation shape:

```text
mock-client [global flags] <subcommand> [subcommand flags]
```

Global flags (`--config`, `--help`, `--version`, plus the 38 config options)
are declared on the root and apply to every subcommand. Each
subcommand also accepts its own `--help`. `launch-ice` and `launch-game`
additionally take a subcommand-local `--duration-seconds` flag, `ice-smoke`
a `--timeout-seconds` flag, and `session` `--peers`, `--peer-refresh-token-file` and
`--peer-access-token-file`.

`--version` prints `mock-client <version>`, where the version is read from the
jar manifest, so a release jar reports the version it was released as. Every
subcommand prints the same line. Run from classes (`./gradlew run`, an IDE) there
is no manifest, and it prints `mock-client (development build)` instead.

## Exit codes

| Code | Constant          | When                                                                             |
|------|-------------------|----------------------------------------------------------------------------------|
| `0`  | `OK`              | Successful run; `--help` and `--version`. For `ice-smoke`: the adapter is reachable. For `session`: a full mesh and two-way game traffic between every pair, with no adapter or game left running. |
| `2`  | `USAGE`           | Bad invocation: invalid args, missing required options, unknown subcommand, no subcommand, unreadable config file, malformed JSON, bad URI, bad port. For `session`, also a `--peers` outside 2 to 26, no peer credential files or both channels at one layer, fewer credential files than peers, two peers on one file or (on access tokens) one account, a refresh-token path that is not a regular file, an unreadable or empty file, a missing binary, a `--log-level` above INFO, or `INSTANCE_NAME` set, all refused before any process starts. |
| `70` | `RUNTIME`         | A runtime failure after a subcommand started, e.g. `run` had no usable refresh-token file, the lobby session failed, or its ICE adapter or game never came up (a binary that could not be started, an adapter that exited or never accepted its JSON-RPC connection, or one that refused a setup call; logged as `the ICE adapter or game never came up`, after a line naming the cause), `launch-ice` / `launch-game` could not find/start its binary, the child exited before its run window, `launch-ice` could not attach a JSON-RPC peer to the adapter it started (WBS-3.1.6.3), `ice-smoke` returned any verdict other than reachable, or a `session` checkpoint failed (including no two-way game traffic) or a subprocess survived its teardown. Also any exception that escapes a subcommand uncaught. |
| `71` | `GAME_CRASHED`    | `run` only: the session ran, but the game process died unaccounted for: a non-zero exit with no `GameEnded` frame observed and no harness-initiated teardown, the same condition that logs `mock-game exited abnormally`. Covers a game process that exited before its match as well as one that died mid-match (a game binary that could not be started at all is `70`), but not mock-game's own `69` (`ADAPTER_LOST`), which is logged as a lost adapter link and leaves this code alone; the adapter's own death is `72` below. Before this existed, such a run exited `0`. |
| `72` | `ADAPTER_LOST`    | `run` only: the session ran, but the ICE adapter process exited non-zero while it was live, outside harness teardown, the same condition that logs `ICE adapter exited abnormally`. Keyed on the adapter's own exit rather than on the game reporting a lost link, so it is decided before the run's terminal state is observable whenever the adapter's death is what ends the session, which is what a killed adapter does. Not guaranteed in the rare case where something else ends it first in the same few milliseconds (an adapter RPC call failing in flight, or the game's own exit being processed first): the run then exits `0`. An adapter that was up and then died, not one that never came up: a failed launch ends the session through a failed transition instead, and an adapter rejecting an argument exits `0` while doing so, so that case is `70`. A clean adapter quit (exit `0`) and a teardown-initiated exit are not this. Before this existed, such a run exited `0`. |

When one `run` has more than one finding, it reports the first of `70` (the lobby
connection dropped, then a launch that never came up), `72` and `71`, and logs
only that one's reporting line. A failed launch cannot meet `72` or `71` in
practice, since no session ran for an adapter or a game to die in. The adapter
comes before the game because its verdict is the one decided before the run's end
is observable, and because an adapter's death is what makes the game react, never
the reverse. A run that a signal ended logs none of these lines, because its exit
code is the signal's own, unless the session's own end and the signal land in the
same instant.

No subcommand returns `64` (`NOT_IMPLEMENTED`) — the constant no longer exists.
Nothing shipped here is a placeholder.

**No parse failure and no subcommand failure produces a code outside this
table** — in particular, never `1`. Picocli's own default for an exception
escaping a subcommand is `ExitCode.SOFTWARE` (`1`) plus a stack trace on stderr;
`ExecutionExceptionHandler` replaces that with a single line naming the command
and the cause, and returns `70`. The stack trace is not discarded — it is logged
at `DEBUG`, so `--log-level DEBUG` puts the whole trace in the JSONL file as one
record's `exception` field (and, being a normal log record, on the console too).
The failure itself is recorded at `ERROR` regardless of level, so a harness
reading log records alone still sees it. Both hold even when the failure lands
before the subcommand itself starts: `--log-level` and `--log-file` are applied
once after parsing and before any subcommand runs, so the records go to the file
you asked for rather than to the default one.

Note that this is the `--log-level` flag and its `FAF_MOCK_CLIENT_LOG_LEVEL`
counterpart. A bare `LOG_LEVEL` variable in the environment is Logback's own
channel and is overridden by the resolved value, on this path and on every
other.

Two things sit outside the table by design, not by oversight. A **signal** exits
with the JVM's signal code — `130` for SIGINT, `143` for SIGTERM — which is the
documented, intended exit path for `run` (see above). And an **`Error`** rather
than an `Exception` — `OutOfMemoryError`, `StackOverflowError` — propagates out
of picocli, which catches only `Exception`; the JVM then prints it and exits `1`.
A consumer should treat any code outside the table as an abnormal termination
rather than a reportable failure mode.

`USAGE` matches picocli's default `CommandLine.ExitCode.USAGE` so picocli's
parameter-exception path needs no remap. Constants live in
`com.faforever.testharness.client.cli.ExitCodes`, and every row is pinned by a
test in `MockClientCliExitCodeTest`.

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

These four are the whole story for `logLevel` too. A bare `LOG_LEVEL` in the
environment is Logback's own channel and is **not** a fifth layer: the value
resolved from the four above — including the built-in default of `INFO` — is
written to the `LOG_LEVEL` *system property* before the first logger exists, and
Logback resolves system properties ahead of environment variables. So
`LOG_LEVEL=DEBUG mock-client run …` produces no `DEBUG` records; reach for
`--log-level DEBUG` or `FAF_MOCK_CLIENT_LOG_LEVEL=DEBUG` instead.

`mock-game` has no `--log-level` flag and does honour a bare `LOG_LEVEL` — but
only when run directly. Under `mock-client run`, which is the example above,
even that does not apply: `MockGameLauncher` and `IceAdapterLauncher` each
overwrite `LOG_LEVEL` in the child they spawn with `mock-client`'s own resolved
level, so an orchestrated run comes up at that level in all three processes.
`LOG_LEVEL=DEBUG mock-client run …` gives `INFO` everywhere.

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
validation, which only `run` applies. The three diagnostics each validate a
narrower slice: `ice-smoke` and `launch-ice` the `iceAdapter*` / `player*` /
logging fields, and `launch-game` the `mockGame*` / port / `player*` / logging
fields. None of the lobby or OAuth rows applies to any of them.

| JSON key | Env var | CLI flag | Default | Required | Description |
|---|---|---|---|---|---|
| `lobbyWebSocketUrl` | `FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL` | `--lobby-websocket-url` | — | yes | WebSocket endpoint of the FAF lobby server. |
| `oauthTokenUrl` | `FAF_MOCK_CLIENT_OAUTH_TOKEN_URL` | `--oauth-token-url` | — | yes² | OAuth2 token endpoint (Hydra `/oauth2/token`). |
| `oauthAuthEndpoint` | `FAF_MOCK_CLIENT_OAUTH_AUTH_ENDPOINT` | `--oauth-auth-endpoint` | — | no | OAuth2 authorization endpoint, used by the one-time refresh-token bootstrap. |
| `oauthRedirectUri` | `FAF_MOCK_CLIENT_OAUTH_REDIRECT_URI` | `--oauth-redirect-uri` | — | no | Redirect URI registered on the OAuth client. |
| `oauthScopes` | `FAF_MOCK_CLIENT_OAUTH_SCOPES` | `--oauth-scopes` | — | no | Space-separated OAuth2 scopes (e.g. `openid offline lobby`). |
| `oauthClientId` | `FAF_MOCK_CLIENT_OAUTH_CLIENT_ID` | `--oauth-client-id` | — | yes² | OAuth2 public client identifier. |
| `oauthRefreshTokenFile` | `FAF_MOCK_CLIENT_OAUTH_REFRESH_TOKEN_FILE` | `--oauth-refresh-token-file` | — | yes¹ | Path to the file holding the long-lived refresh token (sensitive); rewritten atomically on each rotation. |
| `oauthAccessTokenFile` | `FAF_MOCK_CLIENT_OAUTH_ACCESS_TOKEN_FILE` | `--oauth-access-token-file` | — | yes¹ | Path to a file holding a pre-signed access token, sent as-is with no exchange and no renewal (WBS 3.1.6.4). Mutually exclusive with `oauthRefreshTokenFile`; exactly one of the two is required. On this channel `oauthTokenUrl` and `oauthClientId` are not needed, since nothing is exchanged. An expired token surfaces as the lobby's own rejection — a static token cannot renew itself. What the lobby requires of the token, and how to tell one rejection from another, is in the access-token section of runbook §3. |
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
| `iceRelayDelayMs` | `FAF_MOCK_CLIENT_ICE_RELAY_DELAY_MS` | `--ice-relay-delay-ms` | `0` | no | Milliseconds to delay every relayed ICE candidate, both directions, for fault injection (WBS 5.1). `0` relays inline. Delays signalling only, never drops or reorders. See [Fault injection](../documentation/operations/harness-runbook.md#10-fault-injection-wbs-51-52). |
| `mockGameUdpDropPercent` | `FAF_MOCK_CLIENT_MOCK_GAME_UDP_DROP_PERCENT` | `--mock-game-udp-drop-percent` | `0` | no | Percentage of outbound peer datagrams the launched mock-game suppresses — the lossy-link half of the same fault injection (WBS 5.1). Passed through as mock-game's `--udp-drop-percent`, and emitted only when non-zero. Drawn independently per peer per round; dropped datagrams still consume their sequence number, so the receiver sees gaps. |
| `mockGameCrashAfterSeconds` | `FAF_MOCK_CLIENT_MOCK_GAME_CRASH_AFTER_SECONDS` | `--mock-game-crash-after-seconds` | `-1` | no | Seconds after the launched `mock-game` enters a session before it halts without a shutdown, simulating a game crash (WBS 5.2). Negative never crashes; `0` crashes as soon as the game has a session to lose. Passed through as `--crash-after-seconds`, and only when set. See [Fault injection](../documentation/operations/harness-runbook.md#10-fault-injection-wbs-51-52). |

¹ **Exactly one of the two credential channels is required.** Configuring both
*at different layers* is resolved by the ordinary precedence — CLI flag beats
`FAF_MOCK_CLIENT_*` beats the config file — so an access token on the command
line overrides an `oauthRefreshTokenFile` the shipped example config carries.
Configuring both at the *same* layer is a config error naming them, not a
precedence rule: there is nothing to rank, and the two fail differently, so
silently picking one would hand the operator a failure mode they did not choose.
Omitting both produces a picocli `ParameterException` pointing at the bootstrap
procedure in `documentation/research/lobby-protocol-spec.md` §2 (WBS-2.2.10).

² Required on the refresh-token channel only. Nothing is exchanged for a
pre-signed access token, so with `oauthAccessTokenFile` set these two are not
read and need not be supplied. The three bootstrap settings above
(`oauthAuthEndpoint`, `oauthRedirectUri`, `oauthScopes`) are required by
neither channel — they document the one-time browser procedure that mints a
refresh token, which nothing in this process runs.

Neither channel accepts a literal token value on the command line. For the
refresh token that is a correctness requirement — Hydra rotates it on every use
and the rotated value is persisted back to the file, which a literal option could
not do. For the pre-signed access token it is a hygiene one: a flag value shows up
in `ps` output and in build logs.

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

Root help lists every global flag and the five subcommands. Per-subcommand
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

### `launch-ice` — spawn faf-ice-adapter and hold it up

Spawns the adapter, attaches a JSON-RPC peer, runs it for `--duration-seconds`
(default `10`), terminates it, and logs the exit code. The adapter's output
appears in the logs tagged `[ICEAdapter]`.

The peer is what makes this composable (WBS-3.1.6.3): `faf-ice-adapter` parks
inside `GPGNetClient`'s constructor waiting for its first JSON-RPC client, so
without one it accepts a game's connection and then drops it. With `launch-ice`
running, a separate `launch-game` completes the `GameState Idle` →
`CreateLobby` → `GameState Lobby` handshake — verified against 3.3.14; the
transcript is in
[`harness-runbook.md`](../documentation/operations/harness-runbook.md) §2. If the
peer cannot be attached the run reports `70` (`RUNTIME`) rather than leaving you
an adapter that looks healthy and is not usable. Having no lobby, this diagnostic takes `--id`, `--login`, and
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

The fullest of the three no-account diagnostics: no lobby, no OAuth, and nothing
this harness sends leaves loopback. `launch-ice` and `launch-game` need no
credentials either (WBS-3.1.6.3), but this is the one that reports a verdict
rather than just running a subprocess. Run it as a precondition
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
| `state entry: <STATE>` | The client entered `<STATE>`, one of `CONNECTING`, `IDLE`, `SEARCHING`, `STARTING_GAME`, `HOSTING`, `JOINING`, `PLAYING`, `TERMINATED`. |

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