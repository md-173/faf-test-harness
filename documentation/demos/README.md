# Demos

Sprint-review evidence for the Mock Client. Each demo is a short recording, log
transcript, or screenshot set that proves a deliverable works end to end against
a real environment, captured by hand and committed here.

| Demo | WBS | Proves | Artifact |
|------|-----|--------|----------|
| `lobby-connect-idle` | 3.1.1.4 | `run` connects, authenticates, logs the player id, and sits idle | _to be captured once the lobby is reachable — see below_ |

---

## `lobby-connect-idle` — connect, authenticate, idle (WBS-3.1.1.4)

Demonstrates the umbrella story: one command brings the Mock Client up against
the lobby, runs the full handshake, and holds an idle connection alive on the
ping/pong heartbeat until interrupted.

### ⚠️ Network requirement

We have so far been **unable to reach `lobby.faforever.xyz:443`** from several
dev machines/networks — the TCP connect just times out (no refusal, no TLS, no
HTTP response). The OAuth/Hydra token exchange to `hydra.faforever.xyz` works
from the same machines, which suggests the cause is on the network/lobby side
rather than our client, but **we have not confirmed why** (allowlist, VPN,
outage, or a different endpoint are all still possible — pending confirmation
from the FAF team). Until that's resolved, capture this demo from a machine that
can reach the lobby. Confirm reachability first:

```bash
# expect: succeeds within a second where the lobby is reachable; hangs otherwise
timeout 5 bash -c 'cat < /dev/null > /dev/tcp/lobby.faforever.xyz/443' \
  && echo REACHABLE || echo UNREACHABLE
```

If this prints `UNREACHABLE`, don't spin on it — check the access path with the
FAF team (or use the local stack) before retrying.

### Prerequisites

1. **A bootstrapped refresh token** at `.secrets/refresh_token.txt` (gitignored).
   `run` authenticates via the refresh-token **file** channel — the token is
   rotated and re-persisted on each use, so a literal `--oauth-refresh-token`
   alone is rejected. See `lobby-protocol-spec.md` §2 for the one-time bootstrap.
2. **A config file.** Copy the example and point it at the token file:
   ```bash
   cp mock-client/mock-client.example.json mock-client.json
   # ensure: "oauthRefreshTokenFile": "./.secrets/refresh_token.txt"
   ```
   Every endpoint and credential comes from this config — nothing is hardcoded.

### Run

```bash
./gradlew :mock-client:run --args="run --config mock-client.json"
```

Override the lobby URL from the command line or environment to prove the
precedence chain (CLI > env > file):

```bash
# CLI flag wins over the config file
./gradlew :mock-client:run --args="run --config mock-client.json \
  --lobby-websocket-url=wss://lobby.faforever.xyz"

# env var wins over the config file (but loses to a CLI flag)
FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL=wss://lobby.faforever.xyz \
  ./gradlew :mock-client:run --args="run --config mock-client.json"
```

Let it sit for **at least five minutes** to show the heartbeat keeps the
connection alive, then press **Ctrl-C** to close cleanly.

### What to look for in the logs

Each transition is logged on its own line (console + `logs/mockclient.jsonl`):

| Stage | Log line (logger) |
|-------|-------------------|
| Transport up | `lobby WebSocket connected: wss://lobby.faforever.xyz` (`LobbyConnection`) |
| Authenticated | `lobby authenticated as login=<name>` (`LobbyHandshake`) |
| State hydrated | `session ready: id=<id> login=<name>` (`WelcomeStateSync`) |
| Idle | `mock client idle as player id=<id> login=<name>; press Ctrl-C to exit` (`RunCommand`) |
| Heartbeat | the connection stays up across ≥ 5 min — server `ping`s are auto-answered with `pong` (no per-ping line at INFO) |
| Clean shutdown | `shutdown signal received; closing lobby connection` then `lobby WebSocket closed: code=1000 … bucket=LOCAL_CLOSE` |

**No credential ever appears in a log line** — the JWT access token, the refresh
token, and the `unique_id` are never logged; only the server-supplied login and
ids are.

> Note on the exit code: Ctrl-C / SIGTERM closes the socket cleanly via a JVM
> shutdown hook. The process then exits with the signal's conventional code
> (130 for SIGINT, 143 for SIGTERM), not literally 0 — the close itself is clean
> with no error logs, which is what the heartbeat/teardown criteria care about.

### Capturing the artifact

Capture **one** of the following and commit it next to this README:

- **asciinema** (preferred): `asciinema rec documentation/demos/lobby-connect-idle.cast`,
  run the command inside the recording, Ctrl-C, then `exit`.
- **Log transcript**: copy the run's `logs/mockclient.jsonl` (or the console
  output) to `documentation/demos/lobby-connect-idle.log`.
- **Screenshots**: the connect/auth/idle lines and the clean-shutdown lines,
  under `documentation/demos/lobby-connect-idle/`.

Scrub anything sensitive before committing (there should be none — credentials
are never logged — but double-check any pasted shell history for tokens).

### Acceptance criteria → evidence

- Connects, authenticates, receives welcome, logs the player id → the four
  transition lines above.
- Stays idle ≥ 5 min without being dropped → timestamps ≥ 5 min apart with no
  reconnect/disconnect line in between.
- Ctrl-C closes the socket cleanly, no zombie process, no error logs at
  shutdown → the `LOCAL_CLOSE` line and a returned shell prompt.
- No credentials in any log line → inspect the transcript.
- Override chain works end to end → the lobby URL used matches the
  CLI/env override, not the file value.

### Illustrative transcript (not a real capture)

Shows the expected console shape; replace with a real capture once the lobby is
reachable.

```text
[2026-06-17 12:00:00.000] [MockClient] [INFO ] lobby WebSocket connected: wss://lobby.faforever.xyz
[2026-06-17 12:00:00.250] [MockClient] [INFO ] lobby authenticated as login=MockPlayer
[2026-06-17 12:00:00.255] [MockClient] [INFO ] session ready: id=12345 login=MockPlayer
[2026-06-17 12:00:00.256] [MockClient] [INFO ] mock client idle as player id=12345 login=MockPlayer; press Ctrl-C to exit
^C
[2026-06-17 12:05:30.100] [MockClient] [INFO ] shutdown signal received; closing lobby connection
[2026-06-17 12:05:30.140] [MockClient] [INFO ] lobby WebSocket closed: code=1000 reason='' bucket=LOCAL_CLOSE
```
