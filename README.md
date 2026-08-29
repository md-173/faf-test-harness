# faf-test-harness

A headless CLI test harness for [Forged Alliance Forever](https://github.com/FAForever).
Testing any one FAF component has always meant standing up the others: you cannot
exercise the lobby server without a client, or the ICE adapter without two games and
two clients behind them. This harness supplies the missing halves as scriptable
processes, so a component can be tested on its own.

It ships two mocks. **Mock Client** stands in for the [FAF client](https://github.com/FAForever/downlords-faf-client) —
it authenticates against the lobby over WebSocket, hosts or joins a game, launches
and manages a real [`faf-ice-adapter`](https://github.com/FAForever/java-ice-adapter)
subprocess, and relays ICE signalling. **Mock Game** stands in for the Supreme
Commander binary, the one component FAF has never replaced — it speaks the real
GPGNet wire protocol to the adapter, simulates a match, and reports a result.
Both are driven entirely by flags and exit codes, so they compose into CI.

## Running it

One command drives the whole path — the Mock Client launches a real adapter and a
real Mock Game, the game completes its GPGNet handshake, a session plays out, and
teardown leaves nothing running:

```bash
./gradlew downloadIceAdapter
./gradlew :mock-client:integrationTest --tests '*ClientGameLifecycleLiveTest*' --rerun
```

It self-skips rather than fails when the adapter binary or the network is absent,
so it is safe to run unconditionally in someone else's pipeline.

## Documentation
- Contributor workflow & conventions: [CONTRIBUTING.md](CONTRIBUTING.md)
- Setup and single-session runbook, for embedding the mock game in another
  project's tests or running a full client session against the live lobby:
  [documentation/operations/harness-runbook.md](documentation/operations/harness-runbook.md)
- Provision the real ICE adapter the Mock Client drives (`./gradlew downloadIceAdapter`):
  [documentation/operations/ice-adapter-setup.md](documentation/operations/ice-adapter-setup.md)
