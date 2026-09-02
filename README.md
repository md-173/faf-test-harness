# faf-test-harness

A headless CLI test harness for [Forged Alliance Forever](https://github.com/FAForever).
Testing any one FAF component has always meant standing up the others: you cannot
exercise the lobby server without a client, and the ICE adapter's peer-connection path
needs two games and two clients behind it. This harness supplies the missing halves as
scriptable processes, so a component can be tested on its own.

It ships two mocks. **Mock Client** stands in for the
[FAF client](https://github.com/FAForever/downlords-faf-client). It authenticates
against the lobby over WebSocket, hosts or joins a game, launches and manages a real
[`faf-ice-adapter`](https://github.com/FAForever/java-ice-adapter) subprocess, and
relays ICE signalling. **Mock Game** stands in for the Supreme Commander binary, the
one component FAF has never replaced. It speaks the real GPGNet wire protocol to the
adapter, simulates a match, and reports a result.

Both are driven by flags and exit codes, so they compose into CI.

## Try it

Both mocks are published as self-contained jars on the
[releases page](https://github.com/md-173/faf-test-harness/releases); they need a
**Java 21 or newer** runtime. The check below also needs the real adapter:
`faf-ice-adapter-3.3.14-nojfx.jar` from
[java-ice-adapter](https://github.com/FAForever/java-ice-adapter/releases/tag/3.3.14),
which is the version this harness pins.

```bash
java -jar mock-client-0.2.0-all.jar ice-smoke \
  --ice-adapter-binary-path=faf-ice-adapter-3.3.14-nojfx.jar
```

`ice-smoke` spawns the adapter, connects to its JSON-RPC port, sends one request,
connects to its GPGNet port, waits for the adapter to announce that connection back
over RPC, and tears everything down. About two seconds, no FAF account, localhost
only. Exit `0` means reachable; anything else names the phase that failed.

If you already run an adapter of your own, drive a real game against it directly:

```bash
java -jar mock-game-0.2.0-all.jar --gpgnet-port=7237 --lobby-port=7238 \
  --player-id=1 --player-login=test --game-uid=0
```

That gives `CreateLobby` → `GameState Idle` → `GameState Lobby`, with each frame
forwarded to your JSON-RPC peer as `onGpgNetMessageReceived` — which is where you
assert. Attach that peer before the game connects, or the adapter's client setup
blocks. Note the login in `CreateLobby` comes from the adapter's own `--login`, not
`--player-login`.

**The game then waits in the lobby and does not exit on its own.** It is modelling a
game sitting in a lobby, so it advances only when told to: send `hostGame` or
`joinGame` over the adapter's RPC to drive it into a match, or stop the process once
you have asserted what you came for. In CI, give it your own timeout — a run you tear
down reports `SERVER_CONNECTION_LOST` and exits `69` (`ADAPTER_LOST`), which is the
expected shape, not a failure. `0` means the game played a match through to
`GameEnded`; `70` (`RUNTIME`) means it never reached the adapter, or the run failed
some other way; `2` is a bad invocation.

Those two commands are the whole no-clone path. The full client to adapter to game
path, and a session against the live lobby, are in the runbook.

## Documentation

- **Start here.** Setup, the no-lobby path, credentials, and running one client
  session: [documentation/operations/harness-runbook.md](documentation/operations/harness-runbook.md)
- What can be tested in isolation, and which command or Gradle filter proves each
  seam: [documentation/operations/component-isolation.md](documentation/operations/component-isolation.md)
- Mock Client subcommands, flags, config keys and exit codes:
  [mock-client/README.md](mock-client/README.md)
- Provisioning the real ICE adapter, and the upstream quirks worked around:
  [documentation/operations/ice-adapter-setup.md](documentation/operations/ice-adapter-setup.md)
- Captured end-to-end demo transcripts: [documentation/demos/README.md](documentation/demos/README.md)
- Contributor workflow and conventions: [CONTRIBUTING.md](CONTRIBUTING.md)
