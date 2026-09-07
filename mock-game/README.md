# Mock Game

The half of the harness a consumer embeds first. `mock-game` stands in for Forged
Alliance: it speaks the GPGNet protocol to a real `faf-ice-adapter`, plays out a
simulated match on a timer, reports per-army results, and exits with a documented code.

It is a single self-contained jar. It needs no FAF account, no credentials and no
network beyond loopback — everything below runs against an adapter you started
yourself.

```bash
java -jar mock-game-<version>-all.jar \
  --gpgnet-port 21000 --lobby-port 6112 \
  --player-id 42 --player-login Rhiza --game-uid 0
```

## Arguments

Every argument that states a **session fact** — the ports, the identity, the game uid —
is required and never defaulted. A guessed port or player id produces a session that
looks alive and is wrong, so the parser refuses to guess. Unknown arguments fail the
parse.

| Flag | Required | Default | What it is |
| :--- | :--- | :--- | :--- |
| `--gpgnet-port <port>` | yes | — | The adapter's GPGNet TCP port, the one it was started with as `--gpgnet-port`. This is what the game connects *out* to. |
| `--lobby-port <port>` | yes | — | The UDP port the game announces as its lobby port. Peer traffic arrives here. |
| `--player-id <id>` | yes | — | FAF player id of the owning session. |
| `--player-login <login>` | yes | — | FAF player login. Must not be blank. |
| `--game-uid <uid>` | yes | — | Lobby game uid. `0` means no orchestrated session, which is what a standalone run passes. |
| `--game-option <k=v>` | no | none | Extra game options for a host to send. Repeatable. Ignored by a joiner. |
| `--launch-delay-seconds <n>` | no | `5` | How long to sit in the lobby before launching the match unprompted. **Negative never auto-launches** — see below. |
| `--lobby-timeout-seconds <n>` | no | wait forever | How long to wait in the lobby for a `HostGame` or `JoinGame` before giving up and exiting `75`. A game driven into a role never trips it. |

### `--launch-delay-seconds` is the flag a multi-peer host needs

A negative value disables auto-launch entirely, and that is not an edge case — it is
what the host of a two-peer session must pass. The FAF server accepts a `game_join`
only while the game is in `GameState.LOBBY`, and it moves the game out of that state
the moment the **host** reports `GameState Launching`. A host that launches on a timer
therefore makes its own game unjoinable while the joiner is still booting.

The default of `5` is the behaviour `mock-game` had before the flag existed, and it
applies only to a hand-run binary: when the Mock Client orchestrates a run it always
passes the flag explicitly. The startup line says which policy was taken either way.

### `--lobby-timeout-seconds` is about who ends the run

Unset — the default — the game waits in the lobby indefinitely when nothing drives it
into a role. That is faithful to a real game sitting in a lobby, and it is what you
want if you are asserting on the GPGNet handshake: the frames arrive, then the process
stays up so you can assert against it.

The cost is that you have to stop it, and a terminated run exits on the signal (`143`
or `130`) rather than through the codes below — so a run that did exactly what you
asked reports the same way as one killed for hanging. Set this flag and the game gives
up on its own, exiting `75`.

If your CI step already imposes a timeout, `timeout 30 java -jar mock-game-...jar ...`
does the same job from the outside and this flag buys you only the distinct exit code.

## Exit codes

| Code | Name | What produces it |
| :--- | :--- | :--- |
| `0` | `OK` | The match played out and ended normally: every closing frame was handed to the transport without error. |
| `2` | `USAGE` | A missing, unknown, malformed or out-of-range launch argument. Exits before any connect attempt. |
| `69` | `ADAPTER_LOST` | The GPGNet connection was established and then went down mid-session. The game booted and connected fine; the link did not survive. |
| `70` | `RUNTIME` | Never reached the adapter within the connect window, or the run failed for a reason with no more specific code. |
| `75` | `LOBBY_TIMEOUT` | `--lobby-timeout-seconds` elapsed with nothing driving the game into a role. Only reachable when that flag is set. |
| `1` | — | An unchecked throw escaped the bootstrap. The JVM's uncaught-exception default, not a code this program sets. No modelled failure produces it. |
| `143` / `130` | — | `SIGTERM` / Ctrl-C. The JVM's own signal defaults. **A signal always wins**: a terminated run reports the signal's code, never one of the above. |

`0` means the game completed its own program. It is not a claim that the closing frames
were *delivered* — a `send` returns once the kernel accepts the bytes, so a game whose
adapter died as the match ended can write every closing frame into a dead socket and
still exit `0`. The Mock Client is what tells those apart, by reading whether it ever
observed the `GameEnded` frame coming back.

`69` is deliberately worded as the connection going down rather than the adapter
dropping it: the transport cannot tell those apart, so a truncated frame or a decoder
bug arrives identically to a peer that went away. The log line carries the underlying
error.

## The two log lines to assert on

A pipeline needs one line to confirm the run started with the configuration it meant,
and one to confirm how it ended. These two are stable:

```text
mock game started: playerId=42 login=Rhiza gameUid=0 gpgNetPort=21000 lobbyPort=6112 gameOptions={} launch=manual only (auto-launch disabled)
mock game finished: status=SERVER_NOT_CONNECTED, exit code 70
```

The startup line echoes the **resolved** configuration, including the launch policy in
words rather than as raw seconds, so a hand-run binary that took the default still says
out loud what it intends to do. The finish line names the internal status alongside the
code, which is the more specific of the two — several statuses can map to one code.

Both go to stdout in the human-readable format above and to the JSONL log file in
machine-readable form.

## Logging

| Variable | Default | Effect |
| :--- | :--- | :--- |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARN` or `ERROR`. |
| `LOG_FILE` | `logs/mockgame.jsonl` | Path of the JSONL log. A path the log rotator cannot use is reported on stderr and replaced by the default rather than taking logging down with it. |
| `INSTANCE_NAME` | unset | Labels this instance in every record, so several concurrent games stay attributable line by line. A named instance also gets its own default log file, `logs/mockgame-<name>.jsonl`. |

Running the jar creates a `logs/` directory in the working directory, from `LOG_FILE`'s
default. Set `LOG_FILE` to put it somewhere else — in CI, somewhere the workspace
cleanup will find it.

`LOG_DIR` is **not** read by `mock-game`. It is the ICE adapter's variable, and the
harness sets it per child when it spawns one; see
[documentation/research/subprocess-orchestration-spec.md](../documentation/research/subprocess-orchestration-spec.md)
§2.3.

## What it does, in order

1. Parses and validates the argv. A bad argument exits `2` before a socket is opened.
2. Connects to the adapter's GPGNet port, with bounded retries. Failure to reach it
   inside the window ends the run at `70`.
3. Announces itself (`GameState Idle`), then answers the adapter's `CreateLobby` with
   `GameState Lobby`.
4. Waits for a role. `HostGame` makes it a host and it sends its `PlayerOption` set;
   `JoinGame` makes it a joiner.
5. Launches the match — on the launch-delay timer, or when told to.
6. Plays out the match for its duration, then reports one `GameResult` per army,
   `JsonStats`, `GameEnded` and `GameState Ended`, and exits `0`.

The end-of-match result is fixed by design: army 1 wins and every other army loses, on
every run. The harness asserts on the shape and ordering of those closing frames, and a
result that varied would make those assertions depend on configuration nothing has
asked to vary.

## See also

- [documentation/operations/harness-runbook.md](../documentation/operations/harness-runbook.md)
  — setup, the no-lobby path, and running the full client-to-adapter-to-game chain.
- [mock-client/README.md](../mock-client/README.md) — the other half, including the
  subcommands that spawn this one.
- [documentation/operations/ice-adapter-setup.md](../documentation/operations/ice-adapter-setup.md)
  — provisioning the real adapter this talks to.
