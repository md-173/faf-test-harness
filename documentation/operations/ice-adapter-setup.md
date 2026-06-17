# Local `faf-ice-adapter` Setup (WBS 3.1.4.4)

How to provision and run the **real** upstream `faf-ice-adapter` headless in the local dev
environment, so the Mock Client's ICE work (R35/R36) and the live smoke test (R71) are validated
against reality. Per the project rule, the ICE adapter is **never mocked** — we run the real binary.

This is the binary that [`IceAdapterLauncher`](../../mock-client/src/main/java/com/faforever/testharness/client/process/IceAdapterLauncher.java)
spawns. See also `documentation/research/json-rpc-spec.md` §1/§6/§8 and
`documentation/research/subprocess-orchestration-spec.md` §1.1/§2.6–2.7.

## Pinned version

| | |
|---|---|
| Implementation | [`FAForever/java-ice-adapter`](https://github.com/FAForever/java-ice-adapter) |
| **Pinned version** | **`3.3.14`** (set in [`gradle.properties`](../../gradle.properties) as `iceAdapterVersion`) |
| Artifact | `faf-ice-adapter-3.3.14-nojfx.jar` (18,291,350 bytes) |
| SHA-256 | `5d1348f57d29e51c92e5a80380e4cf0dec85f867bdc6f58d8c4e5b5fc01d8281` (in [`gradle.properties`](../../gradle.properties) as `iceAdapterSha256`) |
| Runtime | **Java 21** (matches the repo toolchain) |

**Why this version / artifact.** `3.3.14` is the current latest release and is exactly what the
production desktop client pins — `downlords-faf-client/gradle.properties` sets `iceAdapterVersion=3.3.14`
and its build downloads `faf-ice-adapter-${version}-nojfx.jar`. The **`-nojfx`** ("no JavaFX") jar is
the headless artifact; the `-linux`/`-win` jars only add a bundled JavaFX runtime for the optional
debug window, which we never use. Pin — do not track "latest".

## Upgrading the pin

The version **does not auto-update** — the pin + checksum are deliberate (reproducible builds,
supply-chain safety). To move to a new release:

1. Bump `iceAdapterVersion` in [`gradle.properties`](../../gradle.properties).
2. Update `iceAdapterSha256` in [`gradle.properties`](../../gradle.properties) (it sits right next
   to `iceAdapterVersion`, so the pin is a single edit). Get the hash by running the task once (a
   mismatch prints `expected … / actual …`), or compute it:
   `curl -sL https://github.com/FAForever/java-ice-adapter/releases/download/<ver>/faf-ice-adapter-<ver>-nojfx.jar | sha256sum`.
3. `./gradlew downloadIceAdapter` (the checksum must verify), then re-run the `launch-ice` smoke
   check below, probe `status` (see Quick start), and skim the `[ICEAdapter]` output for new or
   changed CLI behaviour.
4. Update the version / SHA-256 table above, and cross-check
   [`downlords-faf-client/gradle.properties`](https://github.com/FAForever/downlords-faf-client/blob/develop/gradle.properties)
   so the harness stays matched to the version real FAF clients deploy.

There is no automatic notification of new releases; track the
[java-ice-adapter releases](https://github.com/FAForever/java-ice-adapter/releases) page or
downlords' `gradle.properties`.

## Quick start (clean checkout)

```bash
# 1. Fetch the pinned, checksum-verified jar to ./faf-ice-adapter.jar (gitignored).
./gradlew downloadIceAdapter

# 2. Confirm the R34 launcher can spawn it headless and capture its output (12s smoke run).
mock-client/build/install/mock-client/bin/mock-client launch-ice --duration-seconds=12 \
  --ice-adapter-binary-path="$PWD/faf-ice-adapter.jar" \
  --lobby-websocket-url=wss://lobby.faforever.xyz \
  --oauth-token-url=https://hydra.faforever.xyz/oauth2/token \
  --oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth \
  --oauth-redirect-uri=http://127.0.0.1 --oauth-scopes="openid offline lobby" \
  --oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8 \
  --unique-id=00000000-0000-0000-0000-000000000000 \
  --oauth-refresh-token=dummy-unused-by-launch-ice
# (build the launcher first if needed: ./gradlew :mock-client:installDist)
```

**Verify the JSON-RPC `status` socket (AC #2).** While the adapter is up — in a second shell during
the `--duration-seconds` window, or against a manually-started adapter — probe `127.0.0.1:7236`:

```bash
python3 - <<'EOF'
import socket
s = socket.create_connection(("127.0.0.1", 7236), timeout=3)
s.sendall(b'{"jsonrpc":"2.0","method":"status","id":1}\n')
print(s.recv(65536).decode())
EOF
# Reply: {"result":"{...\"gpgpnet\":{...}...}","id":1,"jsonrpc":"2.0"} — result is a JSON *string*.
```

`downloadIceAdapter` is idempotent: a re-run verifies the existing jar's SHA-256 and skips the
download. It is deliberately **not** wired into `build`/`check`, so CI stays offline; run it
explicitly. The jar lands at `./faf-ice-adapter.jar`, which is the launcher's default
`--ice-adapter-binary-path`, so `launch-ice` / `run` find it with no extra config.

The OAuth flags above are required by config validation but are **not used** by `launch-ice` (it
only spawns the adapter); any syntactically valid placeholders work.

## Headless caveat — why the launcher overrides logback

Running the `-nojfx` jar on a plain (JavaFX-less) JDK 21 **crashes on startup**, even with no
`--debug-window`/`--info-window`:

```
Exception in thread "main" java.lang.NoClassDefFoundError: javafx/application/Application
    at ...debug.TextAreaLogAppender$TextAreaOutputStream.write(TextAreaLogAppender.java:38)
    at com.faforever.iceadapter.IceAdapter.start(IceAdapter.java:54)   # a LOG.info() call
```

The jar's bundled `logback.xml` unconditionally wires in a JavaFX `TextAreaLogAppender` (for the
debug window). The first log line touches `javafx.application.Application`, which the `-nojfx` jar
does not contain. The production client never hits this because it runs on a JavaFX-bundled JDK
(Liberica Full).

**Our fix (no extra JDK, no 63 MB jar):** for a `.jar` adapter, `IceAdapterLauncher` injects
`-Dlogback.configurationFile=<logs/ice-adapter/logback-headless.xml>`, a console-only config it
materialises at launch. Output still flows into the harness via `ProcessOutputLogger`
(tagged `[ICEAdapter]`). Rejected alternatives: shipping the `-linux` jar (diverges from the
production artifact; 3.5× larger) or requiring a JavaFX JDK (heavier contributor setup).

## `--game-id` is required (3.3.x)

`faf-ice-adapter` 3.3.14 requires `--game-id`; without it the adapter prints usage and exits
**before** binding the RPC port. The launcher now always passes it
(`--ice-adapter-game-id`, config field `iceAdapterGameId`, default `0`). During a full `run`
session this is sourced from the lobby `game_launch.uid`; for the `launch-ice`/`ice-smoke`
diagnostics it is just a placeholder. This flag is absent from `json-rpc-spec.md` §8 and
`subprocess-orchestration-spec.md` §2.6 — those tables predate 3.3.x and should be amended.

## Verification record (2026-06-17, JDK 21.0.11)

`launch-ice` against the real `3.3.14` jar — adapter spawned → JSON-RPC socket open → `status`
answered → clean teardown:

```
[MockClient] Launching ICE adapter: .../java
  -Dlogback.configurationFile=.../logs/ice-adapter/logback-headless.xml
  -jar .../faf-ice-adapter.jar --id 1 --login Rhiza --game-id 12345
  --rpc-port 7236 --gpgnet-port 7237 --lobby-port 7238
[ICEAdapter] c.f.i.IceAdapter - Version: SNAPSHOT
[ICEAdapter] c.f.i.g.GPGNetServer - GPGNetServer started
[ICEAdapter] c.f.i.rpc.RPCService - Creating RPC server on port 7236
[ICEAdapter] c.n.jjsonrpc.TcpServer - TCP Server started.
[MockClient] Run window of 12s elapsed; terminating ICE adapter
[MockClient] ICE adapter terminated; exit code 143
```

`status` response captured from `127.0.0.1:7236` (matches `json-rpc-spec.md` §6, modulo the notes
below):

```json
{"result":"{\"version\":\"SNAPSHOT\",\"ice_servers_size\":0,\"lobby_port\":7238,
  \"init_mode\":\"normal\",\"options\":{\"player_id\":1,\"player_login\":\"Rhiza\",
  \"rpc_port\":7236,\"gpgnet_port\":7237},\"gpgpnet\":{\"local_port\":7237,
  \"connected\":false,\"game_state\":\"\",\"task_string\":\"-\"},\"relays\":[]}",
 "id":1,"jsonrpc":"2.0"}
```

> **AC #2 is verified manually and recorded here.** The durable, re-runnable connect+`status` check
> is owned by **WBS 3.1.4.3 (ice-smoke, #151)**, which drives it through the 3.1.4.1
> `IceAdapterConnection` (#155); it is gated on this task (the real binary) plus #155 (the
> transport). Re-run the probe (Quick start) after any version bump.

### Notes / gotchas (relevant to downstream ICE tasks, not this task)

- **`status` `result` is a double-encoded JSON *string***, not a nested object. The
  `IceAdapterConnection` status DTO (R35 / PR #155) must `JSON.parse` `result` again.
- **The status field is misspelled `gpgpnet`** (extra `p`) in 3.3.14, not `gpgnet` as in
  `json-rpc-spec.md` §6. Parse the real key.
- **Telemetry phone-home:** on launch the adapter opens a websocket to
  `ice-telemetry.faforever.com`. 3.3.14 has **no clean disable** — `--telemetry-server=""` just
  fails with `unknown scheme: null`, and an unreachable host errors too; either way telemetry
  failure is **non-blocking** (the adapter still binds and answers `status`). In offline CI it
  logs an error and continues. Left as-is — a flag that only changes which error is logged isn't
  worth plumbing.
- Runtime reports `Version: SNAPSHOT` — a cosmetic upstream build-stamp quirk; the artifact is the
  `3.3.14` release.

## Out of scope (here)

STUN/TURN configuration (`setIceServers`, arrives later), actual ICE negotiation / peer
connectivity (needs peers — R71 / multi-peer), and CI integration of the adapter (decide
separately). This task only provisions the binary and proves it binds + answers `status` headless.

## Sources

- [`FAForever/java-ice-adapter` releases](https://github.com/FAForever/java-ice-adapter/releases) — version, assets
- [`downlords-faf-client/gradle.properties`](https://github.com/FAForever/downlords-faf-client/blob/develop/gradle.properties) — authoritative `iceAdapterVersion`
- `documentation/research/json-rpc-spec.md` §1 (transport), §6 (`status`), §8 (CLI args)
- `documentation/research/subprocess-orchestration-spec.md` §2.6–2.7 (adapter launch)
