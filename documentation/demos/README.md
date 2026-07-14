# Demos

Sprint-review evidence for the Mock Client. Each demo is a short recording, log
transcript, or screenshot set that proves a deliverable works end to end against
a real environment, captured by hand and committed here.

| Demo | WBS | Proves | Artifact |
|------|-----|--------|----------|
| `lobby-connect-idle` | 3.1.1.4 | `run` connects, authenticates, logs the player id, and sits idle | ✅ [`lobby-connect-idle.log`](lobby-connect-idle.log) (live capture, 2026-06-18) |

---

## `lobby-connect-idle` — connect, authenticate, idle (WBS-3.1.1.4)

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

| Stage | Log line (logger) |
|-------|-------------------|
| Transport up | `lobby WebSocket connected: wss://ws.faforever.xyz` (`LobbyConnection`) |
| UID generated | `generated unique_id via faf-uid (<n> chars)` (`LobbyHandshake`) |
| Authenticated | `lobby authenticated as login=<name>` (`LobbyHandshake`) |
| State hydrated | `session ready: id=<id> login=<name>` (`WelcomeStateSync`) |
| Idle | `mock client idle as player id=<id> login=<name>; press Ctrl-C to exit` (`RunCommand`) |
| Heartbeat | the connection stays up across ≥ 5 min — server `ping`s are auto-answered with `pong` (no per-ping line at INFO) |
| Clean shutdown | `shutdown signal received; closing lobby connection` (`RunCommand`) |

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
