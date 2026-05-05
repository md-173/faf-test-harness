# Mock Client CLI Subcommand Scaffolding — Implementation Guide

**WBS-3.1.5.2** (this issue) under **WBS-3.1.5** (Mock Client Foundation), sibling of **WBS-3.1.5.1** (config loader, already merged in commit `2dc73f5`).

Scope: a headless Picocli-driven entry point with four subcommand stubs (`run`, `launch-ice`, `launch-game`, `ice-smoke`) on top of the existing config loader. **No business logic** in the CLI layer — every subcommand delegates to a TODO.

This guide is a roadmap, not a code drop. It tells you what to add, what to leave alone, what decisions to make first, what tests to extend, and where the traps are.

---

## 0. TL;DR

- The config loader (WBS-3.1.5.1) already covers ~70% of this work: `MockClientCli` is a `@Command` with all 16 config flags; `LayeredDefaultProvider` does env+file resolution; `ConfigLoader.load(args, env)` is the headless test seam.
- What's missing: subcommand classes, `@Command(subcommands=…)` on the root, picocli's `execute()` lifecycle in `Main`, an exit-code table, and tests for dispatch / unknown-subcommand / per-subcommand `--help`.
- **One decision drives everything else:** keep config flags on the root command and let subcommands access them via `@ParentCommand`. Don't duplicate flags onto each subcommand.
- Preserve `ConfigLoader.load(args, env)` so the 11 existing tests (`ConfigLoader*Test`, `MockClientCliRecordSyncTest`, `LayeredDefaultProviderTest`) keep passing.
- This is a scaffolding-only ticket. Each subcommand `call()` returns a deliberately distinguishable exit code (proposed: `64` "not implemented") so CI cannot mistake a stub for a real success.

---

## 1. Issue requirements → implementation strategy

Every requirement and acceptance criterion in the issue, mapped to the work that satisfies it.

### Objectives

| Objective | Where it lands |
|---|---|
| Use Picocli | Already in use (`info.picocli:picocli:4.7.6` on the mock-client classpath; `@Command` / `@Option` annotations on `MockClientCli`). No change. |
| Root command `mock-client` with `--config`, `--log-level`, `--help`, `--version` | `mock-client` name and `mixinStandardHelpOptions = true` already set on `MockClientCli` (gives `-h/--help` and `-V/--version`). `--config` and `--log-level` already declared as `@Option`s. **Make sure the root `@Command` declares `version`** so `--version` shows something useful; today it likely prints empty. |
| Subcommand stubs `run`, `launch-ice`, `launch-game`, `ice-smoke` | Four new `Callable<Integer>` classes in a new `client.cli` package. Each implements `call()` → log a TODO line, return `NOT_IMPLEMENTED`. |
| Every `MockClientConfig` field exposed as a flag with kebab-case naming | Done. The `MockClientCliRecordSyncTest` already enforces drift-free symmetry between record components and `@Option` fields, including kebab-case ↔ camelCase mapping. |
| Defined exit codes | New: `ExitCodes` constants class. See §11. |
| Auto-generated `--help` on root and every subcommand | Free from picocli when each subcommand is registered as a class with `mixinStandardHelpOptions = true`. |

### Deliverables

| Deliverable | File(s) | Notes |
|---|---|---|
| `MockClientCli` wired as the executable entry point | `Main.java`, `MockClientCli.java` | Switch from `ConfigLoader.load(args)` + manual exit to `new CommandLine(new MockClientCli()).execute(args)`. The `application { mainClass = '…client.Main' }` block in `mock-client/build.gradle` stays. |
| Subcommand stub classes | `client/cli/RunCommand.java`, `LaunchIceCommand.java`, `LaunchGameCommand.java`, `IceSmokeCommand.java` | Each is ~30 lines: annotation + `@ParentCommand` + `Callable<Integer>.call()`. |
| Exit-code reference table in `mock-client/README.md` | `mock-client/README.md` | New section, see §11 + §15. |
| README section: subcommands, global flags, flag-to-config mapping, three example invocations | `mock-client/README.md` | Existing flag-to-config table covers most of "flag-to-config mapping". Add the subcommand table and three new invocation examples. See §15. |

### Acceptance criteria

| Criterion | How it's demonstrated |
|---|---|
| `mock-client --help` and `mock-client <subcommand> --help` print usable help | New tests `MockClientCliSubcommandHelpTest`. The existing `ConfigLoaderHelpTest` continues to assert the root help mentions every required flag. |
| Every config field has a working flag override | Already covered by `ConfigLoaderCliOnlyTest` + `MockClientCliRecordSyncTest`. Add one new test: `mock-client run --lobby-websocket-url=…` reaches the subcommand's `call()` with that URL in the resolved config. |
| Invalid arguments → clear error + non-zero exit | Existing `ConfigLoaderInvalidValuesTest` covers the underlying parsing. Add a thin test that drives `CommandLine.execute(...)` and asserts `!= 0`. |
| Unknown subcommands → friendly error + non-zero exit | New test `MockClientCliUnknownSubcommandTest`. Picocli's default message is acceptable; consider `CommandLine.IParameterExceptionHandler` only if you want suggestions like "did you mean `run`?". |
| Unit tests cover help output, flag plumbing, invalid args, subcommand dispatch | See §14 — five new test classes total. |

### Notes from the issue, addressed

- **Library choice** — Picocli, decided. No re-debate.
- **Subcommand scope** — scaffolding only. Real logic is owned by other tracks. Stubs return `NOT_IMPLEMENTED`.
- **Naming** — kebab-case flags, screaming-snake env vars, camelCase JSON. Already enforced. The env prefix is `FAF_MOCK_CLIENT_` (note: not `MOCK_CLIENT_` as the issue text suggests — the actual prefix is in `LayeredDefaultProvider.ENV_PREFIX`). If the spec really wants `MOCK_CLIENT_*`, that's a separate decision; flag it before changing.
- **R32 / oauth-refresh-token / lobby-url** — the issue mentions flags (`--oauth-refresh-token`, `--lobby-url`) that **do not exist** in the current `MockClientCli`. The actual fields are `--oauth-token-url`, `--oauth-access-token`, `--oauth-token-file`, `--lobby-websocket-url`. There is no refresh-token field. Either R32 has drifted from `MockClientConfig`, or the issue text is informal. **Do not silently rename or add fields**; raise this as an open question (see §19). The drift-guard test would fail anyway if you tried.

---

## 2. Current state — deep read

You have to know what's there before you can extend it. This is the existing surface you're building on.

### 2.1 `MockClientCli.java` (158 lines)

- `@Command(name = "mock-client", mixinStandardHelpOptions = true, description = "…")`.
- 16 `@Option`-annotated fields covering every record component of `MockClientConfig` plus one CLI-only `--config` field.
- Field order matches record-component order; flag names follow strict kebab-case.
- `toConfig()` is package-private and returns a validated `MockClientConfig`.
- The class is **not** a `Callable`/`Runnable`. It's a config holder consumed by `ConfigLoader.load`.

### 2.2 `MockClientConfig.java`

- Immutable record with 16 components.
- Compact constructor enforces "either an access-token-channel or a password-grant-trio" via `IllegalArgumentException`.
- This is the contract between the CLI and every other component.
- **Do not change this in scaffolding work.** New fields belong with the tracks that need them.

### 2.3 `ConfigLoader.java`

- Public API: `Optional<MockClientConfig> load(String[] args)` and `load(String[] args, Map<String,String> env)` (test seam).
- Pre-parses `--config` from raw `args` (picocli needs the path before the default-value provider runs).
- Builds a `CommandLine` around a fresh `MockClientCli`, attaches a `LayeredDefaultProvider`, calls `parseArgs`, handles `isUsageHelpRequested` / `isVersionHelpRequested` by returning `Optional.empty()`, then calls `cli.toConfig()` translating `IllegalArgumentException` → `ParameterException`.
- **Crucial:** every error surfaced from this loader is a `picocli.CommandLine.ParameterException`. That's the contract every existing test asserts on.

### 2.4 `LayeredDefaultProvider.java`

- Implements `IDefaultValueProvider`. Picocli consults it once per option after CLI flags are applied, before `@Option(defaultValue=…)` is consulted.
- Resolution order (highest → lowest): CLI flag → `FAF_MOCK_CLIENT_<UPPER_SNAKE>` env var → camelCase JSON file key → built-in `defaultValue`.
- Reads JSON via Jackson. Hard-fails (`IllegalArgumentException`) on unreadable file, non-object root, parse errors. `ConfigLoader` translates these to `ParameterException`.
- Empty/blank values are treated as absent — important for layered overrides (an env var present but empty doesn't shadow a file value).

### 2.5 `Main.java`

- `static main(String[] args)` does:
  1. `ConfigLoader.load(args)`, catching `ParameterException` and exiting `2`.
  2. If config present: `applyLoggingProperties(config)` → `LoggingSetup.configure("MockClient")` → log "Mock client started".
- This entry point will collapse into a one-liner around `CommandLine.execute(args)` after the change.

### 2.6 Tests already in place (`mock-client/src/test/java/.../config/`)

11 classes:

| Class | What it locks down |
|---|---|
| `ConfigLoaderCliOnlyTest` | CLI-only resolution → valid config. |
| `ConfigLoaderEnvOnlyTest` | Env-only resolution → valid config. |
| `ConfigLoaderFileOnlyTest` | JSON-file-only resolution → valid config. |
| `ConfigLoaderDefaultsOnlyTest` | Built-in defaults applied where appropriate. |
| `ConfigLoaderPrecedenceTest` | CLI > env > file > defaults, per field. |
| `ConfigLoaderHelpTest` | `--help` and `--version` short-circuit before required-check. Help text mentions every required flag. |
| `ConfigLoaderInvalidValuesTest` | Bad ports, bad URIs, missing/unreadable/non-object config files all surface as `ParameterException` with informative messages. |
| `ConfigLoaderAuthChoiceTest` | Cross-field auth validation (token vs password-grant). |
| `LayeredDefaultProviderTest` | Provider-level resolution semantics. |
| `MockClientCliRecordSyncTest` | Drift guard: CLI ↔ record name + count parity, kebab↔camel agreement. |
| `TestFixtures` | Shared minimal-required-CLI args, env map, JSON, and assertion helpers. |

These tests anchor the codebase. Treat `ConfigLoader.load(args, env)` as a **stable public API** for the duration of this work. New tests stack on top; old tests must continue to pass without modification (ideally) or with mechanical updates only.

### 2.7 `mock-client.example.json`

Mirror of the JSON shape the file-resolver expects. If you add a new flag (you shouldn't, in scaffolding), update this file in the same commit.

### 2.8 `mock-client/README.md`

Already documents:
- The four-source precedence chain.
- The env-var convention with `FAF_MOCK_CLIENT_` prefix.
- A complete flag-to-env-to-JSON-key reference table for all 16 fields.
- Five invocation examples (help, file-only, env-only, CLI-only, layered) plus a multi-client example.
- The failure mode (missing-required output + exit 2).

The new content (subcommand list, exit-code table, three subcommand examples) **slots into** this structure rather than replacing any of it.

### 2.9 `mock-client/build.gradle`

```
plugins { id 'java'; id 'application'; id 'checkstyle'; id 'com.diffplug.spotless' }
application { mainClass = 'com.faforever.testharness.client.Main' }
implementation 'info.picocli:picocli:4.7.6'
implementation project(':shared')
```

The Gradle `application` plugin emits a `build/install/mock-client/bin/mock-client` wrapper script. After this change, that wrapper becomes the canonical way to run subcommands without going through `:mock-client:run --args="…"`.

---

## 3. Target architecture

```
                                                     execute(args) returns int
                                                     ┌───────────┐
                          parses args, picks    ┌───►│ exit code │
                          subcommand, populates │    └───────────┘
                          @ParentCommand fields │
                                                │
   args ───► CommandLine ──────────────────────►│
                ▲                               │
                │                               ▼
                │                          ┌─────────────────────┐
                │       holds 16 ◄────────►│   MockClientCli     │  @Command(name="mock-client",
                │       config flags +     │   (root command)    │            subcommands={…})
                │       --config           └──────────┬──────────┘  Callable<Integer>
                │                                     │
                │                          ┌──────────┼──────────┬──────────────┐
                │                          ▼          ▼          ▼              ▼
                │                       run     launch-ice  launch-game     ice-smoke
                │                        │           │           │              │
                │            each: @ParentCommand MockClientCli parent;
                │                       │           │           │              │
                │                       └─── parent.toValidatedConfig() ───────┘
                │                                          │
                │                                          ▼
                │                                  MockClientConfig
                │                                          │
                │                                          ▼
                │                            applyLoggingProperties + log "TODO"
                │                                          │
                │                                          ▼
                │                                    return NOT_IMPLEMENTED
                │
   LayeredDefaultProvider attached so subcommand inherits env+file resolution
```

The root command holds the config flags. Subcommands inherit the same parsing rules because picocli walks the tree once per `execute()`. Each subcommand is small: a `@Command` annotation + `@ParentCommand` + a 5–10-line `call()`.

### Invocation matrix

| Input | Outcome | Exit |
|---|---|---|
| `mock-client --help` | Root usage with `Commands:` listing the four subcommands | `0` |
| `mock-client --version` | Version string from `@Command(version=…)` | `0` |
| `mock-client <sub> --help` | Subcommand-scoped usage | `0` |
| `mock-client run …minimal-valid-config…` | Logs "TODO: run not implemented yet" | `64` |
| `mock-client launch-ice …minimal-valid-config…` | Logs "TODO: launch-ice not implemented yet" | `64` |
| `mock-client` (no subcommand) | Prints root help, friendly hint | `2` |
| `mock-client wat` | "Unmatched argument 'wat'" + root usage | `2` |
| `mock-client run --bogus` | Subcommand-scoped error + that subcommand's usage | `2` |
| `mock-client run` (missing required) | Lists every missing required option | `2` |
| `mock-client run --ice-adapter-rpc-port=NaN` | "Invalid value … for --ice-adapter-rpc-port" | `2` |
| `mock-client run --config /nope.json` | "config file is not readable: /nope.json" | `2` |

---

## 4. Three structural choices — pick one, then move on

The 16 config flags can live in three different places. All three are valid picocli idioms.

### A. **Flags on root, subcommands use `@ParentCommand`** *(recommended)*

```text
@Command(name="mock-client", subcommands={Run.class, …})
class MockClientCli implements Callable<Integer> {
    @Option(names="--lobby-websocket-url") URI lobbyWebSocketUrl;
    // … 15 more @Options …
    public Integer call() { /* no subcommand → print help, exit 2 */ }
    MockClientConfig toValidatedConfig() { … }
}

@Command(name="run", mixinStandardHelpOptions=true)
class RunCommand implements Callable<Integer> {
    @ParentCommand MockClientCli parent;
    public Integer call() {
        MockClientConfig cfg = parent.toValidatedConfig();
        applyLoggingProperties(cfg);
        LoggingSetup.configure("MockClient");
        log.info("TODO: run not implemented yet");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
```

**Pros:** zero duplication; every subcommand shares the same flag set; the existing `MockClientCliRecordSyncTest` works unchanged because there is still exactly one set of `@Option` fields; CLI invocations look natural (`mock-client --config foo.json run`).

**Cons:** all 16 required flags must be supplied even for subcommands that conceptually need fewer (e.g., `launch-ice` arguably doesn't need lobby URLs). For a scaffolding ticket this is fine — defer per-subcommand subsets to the tracks that ship the real logic.

### B. **Flags on each subcommand individually**

Move every `@Option` from `MockClientCli` onto each subcommand class. Root has only `--config` and `--log-level`.

**Pros:** each subcommand can declare exactly the flags it needs; CLI users can run `launch-ice` with a smaller flag set.

**Cons:** 4× duplication; `MockClientCliRecordSyncTest` has to be reworked to scan four classes; bigger diff; risk of subcommand drift over time.

### C. **Mixin** (`@Mixin SharedConfigOptions`)

Put the 16 flags into a `SharedConfigOptions` class. Mix it into the root and/or each subcommand via `@Mixin`.

**Pros:** explicit dependency; reusable if a fifth subcommand appears; flag documentation lives in one place.

**Cons:** an extra layer for the value of being slightly more idiomatic; subcommand classes have to dereference `mixin.lobbyWebSocketUrl()` to see fields, which complicates the drift guard.

### Recommendation

**Choice A.** It minimises diff and preserves every existing test. Revisit B/C only when a real subcommand needs a different flag set — and that work belongs to the track that ships that subcommand.

---

## 5. Decision checklist (settle these before coding)

1. **Structural choice** — A, B, or C. *Recommend A.*
2. **Bare-invocation behavior** — `mock-client` with no subcommand → print help and exit `2` (recommended) or auto-error via `subcommandsRequired = true`.
3. **Bare-root validation** — when does the root `Callable.call()` validate config? *Recommend: it doesn't. Bare root just prints help and exits 2; only subcommands trigger `toValidatedConfig()`.* That way `mock-client --help` doesn't fail required-check.
4. **Stub exit code** — `0` (treat scaffolding as functional) or `64` "not implemented" (recommended). Pick now so tests can assert on it.
5. **Logging on help path** — `LoggingSetup.configure(…)` should fire only when a subcommand actually does work, never on `--help` / `--version` / unknown-subcommand. Move the logging-init out of `Main` and into each subcommand's `call()`.
6. **Picocli `execute()` vs. retaining `ConfigLoader.load()`** — keep both. `Main` uses `execute()`; tests keep using `ConfigLoader.load(args, env)`. They share an internal `newCommandLine(args, env)` factory.
7. **Friendly unknown-subcommand** — picocli's default message is acceptable. A custom `IParameterExceptionHandler` that calls `CommandLine.UnmatchedArgumentException.printSuggestions(…)` is a one-line upgrade if you want "did you mean `run`?". Optional polish.
8. **`@Command(version = …)` value** — wire it from a constant or a manifest attribute. Today nothing prints; that's a latent bug `--version` would expose.
9. **Where do subcommand classes live** — package `com.faforever.testharness.client.cli` (new). Keep `config/` for config-shaped types; `cli/` for command tree types. Or co-locate everything in `config/` for the smallest diff. *Recommend: new `cli/` package.* This means `MockClientCli` and `toConfig()` need to be `public` (or `package-private` with the package opened up), since `cli/` will reference them. See §16 pitfalls.
10. **What does each subcommand mean** — see §7. These need a one-line description even at stub stage; help output is one of the deliverables.

---

## 6. Picocli execution lifecycle, end to end

When `Main` calls `new CommandLine(new MockClientCli()).execute(args)`, picocli runs roughly:

1. **Tokenise `args`** and walk the subcommand tree. The first non-flag token is matched against `subcommands`; if matched, picocli switches the active command and continues parsing the remainder against the subcommand.
2. **Apply CLI flag values** to fields on whichever command(s) they belong to. `@ParentCommand` is wired automatically.
3. **Consult `IDefaultValueProvider`** (i.e., `LayeredDefaultProvider`) once per option not yet set. This pulls env+file values. **The provider attached to the root is inherited by subcommands** — confirm with a test.
4. **Type-convert** values (`String` → `URI`, `String` → `Path`, `String` → `int`). Conversion failure → `ParameterException`.
5. **Required-check** every `required = true` option that's still null. If `--help` or `--version` is requested anywhere in the chain, picocli **skips** required-check and short-circuits to printing usage / version and returns `0`.
6. **Pick the leaf command** (deepest one in the parsed chain) and invoke its `Runnable.run()` or `Callable.call()`.
7. **If `call()` returns `Integer`**, that's the exit code. If `Runnable`, the exit code is `0` unless an exception is thrown.
8. **Exceptions during parsing** are routed through `IParameterExceptionHandler`. Default: print error + usage to stderr, return the configured "invalid input" code (default `2`).
9. **Exceptions during execution** are routed through `IExecutionExceptionHandler`. Default: rethrow wrapped, return the configured "execution error" code.
10. **`execute()` returns the int**; `Main` does `System.exit(...)` with it.

### Key implications for this work

- The `LayeredDefaultProvider` attached on the root **must** be inherited by subcommands. Picocli does this automatically when the provider is registered on the root `CommandLine`. Verify in a test (`mock-client run` with only env vars set, no CLI flags).
- The `--config` pre-parse currently lives in `ConfigLoader.preParseConfigFlag(args)`. With subcommands, `--config` may appear before *or* after the subcommand name on the command line, depending on how the user invokes it. The pre-parser already walks the entire `args` array, so this still works — but add a test for `mock-client run --config foo.json` (config flag *after* subcommand) to be sure.
- `cli.toConfig()` throws `IllegalArgumentException`; subcommands need to translate that to `ParameterException` so picocli's parameter-exception handler produces a clean error+usage block. Either each subcommand catches and rethrows, or you add a helper `MockClientCli.toValidatedConfig(CommandSpec spec)` that does the wrap once. **Centralise this.**
- `--help` on a subcommand short-circuits *before* `@ParentCommand` is needed, so help works even with no required flags supplied. Confirm with a test.

---

## 7. Subcommand designs (stub-level)

Each subcommand needs a name, a one-line description, and a clear placeholder behavior. Below is a reasonable read of what each will eventually do, derived from the architecture diagrams and `task-desc.md`. **Stub behavior** is identical for all four: log a TODO and return `NOT_IMPLEMENTED`. The description is what shows up in `--help`.

| Subcommand | One-line description (for `@Command(description=…)`) | Eventual responsibility |
|---|---|---|
| `run` | "Run a full mock client session: authenticate, queue, play, teardown." | The default end-to-end flow. Spawns `faf-ice-adapter` + `mock-game`, drives the lifecycle FSM, exits when the game ends. The "happy path" CI invocation. |
| `launch-ice` | "Spawn faf-ice-adapter only and forward its output through the harness logger." | Lower-level diagnostic. Useful for testing changes to the ICE adapter itself, or as a stepping stone in CI when wiring up subprocess plumbing. |
| `launch-game` | "Spawn mock-game only and forward its output through the harness logger." | Same as above, for the mock-game subprocess. Pairs with `launch-ice` to drive the GPGNet handshake without lobby involvement. |
| `ice-smoke` | "ICE-adapter connectivity smoke test — bring up the adapter, verify GPGNet handshake, exit." | Goes one step further than `launch-ice`: actually exercises the adapter's GPGNet endpoint as a sanity check. Suitable for a CI "is everything reachable?" gate. |

For the stub PR, all four are identical — only the `name` and `description` strings differ. The eventual divergence belongs to the tracks listed in `documentation/task-desc.md`.

---

## 8. Step-by-step plan

Order matters. Each step keeps the build green, tests passing, and the diff scoped.

### Step 1 — Add the `client.cli` package and four stub classes

New files:
- `mock-client/src/main/java/com/faforever/testharness/client/cli/RunCommand.java`
- `…/cli/LaunchIceCommand.java`
- `…/cli/LaunchGameCommand.java`
- `…/cli/IceSmokeCommand.java`
- `…/cli/package-info.java`

Each stub:
- `@Command(name="<name>", mixinStandardHelpOptions=true, description="…", exitCodeOnExecutionException = ExitCodes.RUNTIME)`.
- `@ParentCommand private MockClientCli parent;`.
- `public Integer call()`:
  1. `MockClientConfig cfg = parent.toValidatedConfig();`
  2. Apply logging properties from `cfg` (move `applyLoggingProperties` from `Main` into a static helper, e.g. `MockClientCli.applyLoggingProperties(cfg)`).
  3. `LoggingSetup.configure(MockClientCli.COMPONENT_NAME);`
  4. `log.info("TODO: <name> not implemented yet (WBS-…)")` — name the WBS ticket that owns the real implementation.
  5. `return ExitCodes.NOT_IMPLEMENTED;`

At this point the project still compiles (subcommand classes exist but aren't wired in). Tests still pass.

### Step 2 — Promote `MockClientCli` to a real root command

Edit `MockClientCli.java`:
- Add to `@Command`: `subcommands = {RunCommand.class, LaunchIceCommand.class, LaunchGameCommand.class, IceSmokeCommand.class}`.
- Add `version = "mock-client 1.0-SNAPSHOT"` (or pull from `MockClientCli.class.getPackage().getImplementationVersion()` once you wire that into the gradle `jar` task — defer that polish).
- Implement `Callable<Integer>`. `call()` body:
  ```text
  spec.commandLine().usage(spec.commandLine().getOut());
  return ExitCodes.USAGE;   // bare root → print help, exit 2
  ```
  (`@Spec CommandSpec spec;` field is the way to get the `CommandLine` reference inside `call()`.)
- Make `toConfig()` `public` and rename to `toValidatedConfig()` for clarity. Update the one existing caller in `ConfigLoader`.
- Make the class itself `public` if subcommand classes live in the new `cli/` package.
- Move `applyLoggingProperties(MockClientConfig)` and `COMPONENT_NAME` from `Main` to a `MockClientCli` static helper (or a new `LoggingBootstrap` class — bikeshed later).

After this step, the build still passes (the four subcommand classes are now reachable via picocli) and so do all 11 existing tests, because `ConfigLoader.load()` still owns its own parsing path (see step 4).

### Step 3 — Add `ExitCodes` constants

New file: `mock-client/src/main/java/com/faforever/testharness/client/cli/ExitCodes.java`

```text
public final class ExitCodes {
    private ExitCodes() {}
    public static final int OK              = 0;
    public static final int USAGE           = 2;   // matches picocli default
    public static final int NOT_IMPLEMENTED = 64;
    public static final int RUNTIME         = 70;  // reserved for future
}
```

Replace `Main.EXIT_CONFIG_ERROR = 2` with `ExitCodes.USAGE`.

### Step 4 — Switch `Main` to `execute()`, but keep `ConfigLoader.load()`

Refactor `ConfigLoader.java`: extract the `CommandLine` construction into a package-private factory.

```text
static CommandLine newCommandLine(String[] args, Map<String,String> env) {
    Path configFile = preParseConfigFlag(args);
    MockClientCli root = new MockClientCli();
    CommandLine cmd = new CommandLine(root);
    cmd.setDefaultValueProvider(new LayeredDefaultProvider(env, configFile));
    return cmd;
}
```

`load(String[] args, Map<String,String> env)` keeps its existing signature and behavior, but is implemented in terms of `newCommandLine`. That keeps every existing test green.

`Main.java` becomes:

```text
public static void main(String[] args) {
    int code = ConfigLoader.newCommandLine(args, System.getenv()).execute(args);
    System.exit(code);
}
```

### Step 5 — Translate `IllegalArgumentException` from `toValidatedConfig()` consistently

Every subcommand's `call()` calls `parent.toValidatedConfig()`. If that throws `IllegalArgumentException` (e.g., the auth-channel guard in `MockClientConfig`), the exception leaks into picocli's *execution* exception handler, which surfaces as a stack trace.

**Fix once, in `MockClientCli`**: change `toValidatedConfig()` to take the `CommandSpec spec` and throw `ParameterException`:

```text
public MockClientConfig toValidatedConfig(CommandSpec spec) {
    try {
        return new MockClientConfig( … );
    } catch (IllegalArgumentException e) {
        throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage(), e);
    }
}
```

Subcommands pass their own `@Spec CommandSpec spec` field. `ConfigLoader.load` does the same translation today; this just centralises it.

### Step 6 — Tests

See §14. Five new test classes, all small.

### Step 7 — README updates

See §15. Three new sections; existing sections unchanged.

### Step 8 — Run the full quality gate locally before opening PR

```text
./gradlew :mock-client:spotlessApply :mock-client:spotlessCheck :mock-client:checkstyleMain :mock-client:checkstyleTest :mock-client:test
./gradlew :mock-client:installDist
./build/install/mock-client/bin/mock-client --help
./build/install/mock-client/bin/mock-client wat
./build/install/mock-client/bin/mock-client run --help
```

Manual smoke of the install-dist binary catches anything that only manifests outside Gradle's `run` task (classpath issues, manifest issues, version-string emptiness).

---

## 9. Files added / changed (concrete inventory)

### Added

| Path | Purpose | Approximate size |
|---|---|---|
| `mock-client/src/main/java/com/faforever/testharness/client/cli/RunCommand.java` | `run` stub | ~30 LoC |
| `…/cli/LaunchIceCommand.java` | `launch-ice` stub | ~30 LoC |
| `…/cli/LaunchGameCommand.java` | `launch-game` stub | ~30 LoC |
| `…/cli/IceSmokeCommand.java` | `ice-smoke` stub | ~30 LoC |
| `…/cli/ExitCodes.java` | Exit-code constants | ~15 LoC |
| `…/cli/package-info.java` | Package JavaDoc per CONTRIBUTING.md | ~5 LoC |
| `mock-client/src/test/java/.../cli/MockClientCliSubcommandHelpTest.java` | `--help` on each subcommand prints usable usage | ~80 LoC |
| `…/cli/MockClientCliDispatchTest.java` | Each subcommand's `call()` is invoked when its name appears in args | ~80 LoC |
| `…/cli/MockClientCliUnknownSubcommandTest.java` | Unknown subcommand → exit 2 + clear error | ~40 LoC |
| `…/cli/MockClientCliNoSubcommandTest.java` | Bare `mock-client` → exit 2 + root help | ~40 LoC |
| `…/cli/MockClientCliExitCodeTest.java` | Exit codes from `execute(...)` match the table | ~80 LoC |

### Changed

| Path | Change |
|---|---|
| `MockClientCli.java` | Add `subcommands = {…}`, implement `Callable<Integer>`, add `@Spec CommandSpec`, add `version`, make `public`, rename `toConfig` → `toValidatedConfig` (taking spec). Move `applyLoggingProperties` here (or to a sibling class). |
| `Main.java` | Body collapses to two lines: build the `CommandLine`, call `execute(args)`, `System.exit`. |
| `ConfigLoader.java` | Extract `newCommandLine(args, env)` package-private factory. `load(args, env)` reuses it. Public surface unchanged. |
| `mock-client/README.md` | Add `Subcommands` section, `Exit codes` section, three new examples (`mock-client run`, `mock-client launch-ice`, `mock-client ice-smoke`). Update the existing examples to show subcommand form (`./gradlew :mock-client:run --args="run --config …"`). |
| `MockClientCliRecordSyncTest.java` | Possibly: update `CLI_ONLY_FIELD_NAMES` if any new CLI-only fields are added (e.g., a `--version` option, but `mixinStandardHelpOptions` makes that automatic — should not require an update). |

### Untouched

`MockClientConfig.java`, `LayeredDefaultProvider.java`, `mock-client.example.json`, `build.gradle`, all 11 existing test classes, `TestFixtures`.

---

## 10. Code shapes (annotation patterns, not full code)

These are sketches showing *how the pieces compose*, not paste-ready code.

### Root command

```text
@Command(
    name = "mock-client",
    mixinStandardHelpOptions = true,
    version = "mock-client 1.0-SNAPSHOT",
    description = "Headless FAF lobby client used by the integration test harness.",
    subcommands = {
        RunCommand.class,
        LaunchIceCommand.class,
        LaunchGameCommand.class,
        IceSmokeCommand.class,
    })
public final class MockClientCli implements Callable<Integer> {

    @Spec CommandSpec spec;

    // ... 16 @Option fields exactly as today ...

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return ExitCodes.USAGE;
    }

    public MockClientConfig toValidatedConfig(CommandSpec callerSpec) {
        try {
            return new MockClientConfig(/* fields */);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(callerSpec.commandLine(), e.getMessage(), e);
        }
    }
}
```

### Subcommand stub (template)

```text
@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = "Run a full mock client session: authenticate, queue, play, teardown.")
public final class RunCommand implements Callable<Integer> {

    @ParentCommand private MockClientCli parent;
    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        MockClientConfig cfg = parent.toValidatedConfig(spec);
        MockClientCli.applyLoggingProperties(cfg);
        LoggingSetup.configure(MockClientCli.COMPONENT_NAME);
        Logger log = LoggerFactory.getLogger(RunCommand.class);
        log.info("TODO: 'run' not implemented yet (WBS-3.1.x)");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
```

### `Main`

```text
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        int code = ConfigLoader.newCommandLine(args, System.getenv()).execute(args);
        System.exit(code);
    }
}
```

### `ConfigLoader` (refactor only)

```text
public final class ConfigLoader {
    public static final String CONFIG_FLAG = "--config";

    public static Optional<MockClientConfig> load(String[] args) {
        return load(args, System.getenv());
    }

    public static Optional<MockClientConfig> load(String[] args, Map<String,String> env) {
        CommandLine cmd = newCommandLine(args, env);
        ParseResult result;
        try {
            result = cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            throw e;
        }
        if (result.isUsageHelpRequested())  { cmd.usage(cmd.getOut()); return Optional.empty(); }
        if (result.isVersionHelpRequested()){ cmd.printVersionHelp(cmd.getOut()); return Optional.empty(); }
        MockClientCli cli = cmd.getCommand();
        return Optional.of(cli.toValidatedConfig(cmd.getCommandSpec()));
    }

    static CommandLine newCommandLine(String[] args, Map<String,String> env) {
        Path configFile = preParseConfigFlag(args);
        MockClientCli root = new MockClientCli();
        CommandLine cmd = new CommandLine(root);
        try {
            cmd.setDefaultValueProvider(new LayeredDefaultProvider(env, configFile));
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(cmd, e.getMessage(), e);
        }
        return cmd;
    }
}
```

This shape preserves the `ConfigLoader.load(args, env)` contract that 11 tests depend on, while exposing `newCommandLine(args, env)` to `Main`. **One source of truth for `CommandLine` construction.**

---

## 11. Exit codes — full table and wiring

### The table

| Code | Constant | Meaning | Sources |
|---|---|---|---|
| `0` | `OK` | Success. | `--help`, `--version`, future successful runs. |
| `2` | `USAGE` | Bad invocation: invalid args, missing required options, unknown subcommand, no subcommand given, unreadable config file, malformed JSON, bad URI, bad port. | Picocli's `ParameterException` path. |
| `64` | `NOT_IMPLEMENTED` | Subcommand acknowledged but its real logic hasn't shipped yet. | Stub `Callable.call()` returns. |
| `70` | `RUNTIME` | Subprocess crash, network failure, FSM error. **Reserved**. Do not return this from scaffolding code; declare it for future use. | Picocli's `IExecutionExceptionHandler`. |

### Why `2` for USAGE

Picocli's `CommandLine.ExitCode.USAGE = 2` is the default value for parameter-exception exit. Aligning means you don't need a custom `IParameterExceptionHandler` just to remap exit codes.

### Why `64` for NOT_IMPLEMENTED

`64` is BSD `EX_USAGE`-adjacent in sysexits.h, but in practice anything outside the small "common" set works. The point is **distinguishability in CI**: a green pipeline cannot accidentally pass through a stub.

If your CI matrix prefers stubs to look successful (`0`), you can flip this — but flag the test that asserts the exit code, and don't rely on log scanning to distinguish stubs from real runs.

### Wiring

- Subcommand `Callable<Integer>.call()` returns the int directly. Picocli uses that as the exit code.
- For uncaught runtime exceptions, set `@Command(exitCodeOnExecutionException = ExitCodes.RUNTIME)` on each subcommand.
- For parameter-exception exit, picocli's default is already `2`. Leave it.
- Document all four codes in `mock-client/README.md`.

---

## 12. Error-handling architecture

Two interceptors govern what happens when things go wrong:

### `IParameterExceptionHandler`

Fires when picocli's parser throws (bad value, unknown flag, missing required, unknown subcommand). Default: prints error + usage to stderr, returns `2`. Acceptable as-is.

**Override only if** you want to upgrade unknown-subcommand to suggest similar names:

```text
cmd.setParameterExceptionHandler((ex, args) -> {
    CommandLine cmd = ex.getCommandLine();
    cmd.getErr().println(ex.getMessage());
    if (ex instanceof UnmatchedArgumentException uae) {
        UnmatchedArgumentException.printSuggestions(uae, cmd.getErr());
    } else {
        cmd.usage(cmd.getErr());
    }
    return cmd.getCommandSpec().exitCodeOnInvalidInput();
});
```

This is a polish item, not a requirement. The acceptance criterion ("friendly error") is satisfied by picocli's default.

### `IExecutionExceptionHandler`

Fires when a subcommand's `call()` throws. Default: prints stack trace, returns `1`. **You should override this** so a thrown `RuntimeException` doesn't dump a stack trace at users:

```text
cmd.setExecutionExceptionHandler((ex, cmd2, parseResult) -> {
    cmd2.getErr().println("error: " + ex.getMessage());
    return ExitCodes.RUNTIME;
});
```

For the scaffolding ticket this is optional — stubs don't throw — but adding it costs nothing and prevents future regressions.

### Translation rule (worth restating)

`MockClientConfig`'s compact constructor throws `IllegalArgumentException`. Picocli routes that through the *execution* handler, not the *parameter* handler — so without translation, a missing-auth-channel error looks like a runtime failure. Translate at the boundary in `MockClientCli.toValidatedConfig(spec)`. See §8 step 5.

---

## 13. README updates — concrete diff plan

`mock-client/README.md` already has:
- intro paragraph
- Configuration → Precedence
- Configuration → Environment variable convention
- Configuration → Field reference (16-row table)
- Configuration → Secrets
- Example invocations (5 examples)
- Failure mode

**Add** (in this order, after the intro paragraph):

1. **`## Subcommands`** — table:

   | Subcommand | Purpose |
   |---|---|
   | `run` | Full session: auth → queue → game → teardown. |
   | `launch-ice` | Spawn `faf-ice-adapter` only. |
   | `launch-game` | Spawn `mock-game` only. |
   | `ice-smoke` | ICE-adapter connectivity smoke test. |

   Note that all four currently log `TODO` and exit `64`. Real logic ships in sibling tracks.

2. **`## Global flags`** — note that `--config`, `--log-level`, `--help`, `--version`, plus all 16 config flags, are declared on the **root** command and apply to every subcommand. CLI form: `mock-client [global flags] <subcommand> [subcommand flags]`.

3. **`## Exit codes`** — the four-row table from §11.

**Update**:

4. Existing examples — every `./gradlew :mock-client:run --args="…"` invocation should be updated to include the subcommand name where appropriate (typically `run`):

   ```
   ./gradlew :mock-client:run --args="run --config mock-client.json"
   ```

   The `--help` example becomes `./gradlew :mock-client:run --args="--help"` (root help) and a new line `./gradlew :mock-client:run --args="run --help"` (subcommand help).

5. **Three new example invocations** (the issue calls these out as a deliverable):

   - `mock-client run --config …` — typical session.
   - `mock-client launch-ice --ice-adapter-binary-path … --ice-adapter-rpc-port 7236` — spawn-only.
   - `mock-client ice-smoke --config …` — smoke test.

   Show each with the gradle wrapper (`./gradlew :mock-client:run --args="…"`) and with the install-dist binary (`./build/install/mock-client/bin/mock-client …`) so users see both options.

The existing flag-to-config-mapping table is the answer to the issue's "flag-to-config mapping" deliverable; it does not need to change.

---

## 14. Test plan — five new classes

All in `mock-client/src/test/java/com/faforever/testharness/client/cli/`. Use a `TestFixtures` import from the existing config package — it already builds minimal-required CLI args.

Pattern for every test below: drive picocli with `CommandLine.execute(args)` (not `parseArgs`), capture stdout/stderr via `StringWriter`, assert on exit code + output.

```text
private CommandLine cmd;
private StringWriter out, err;

@BeforeEach
void setUp() {
    cmd = ConfigLoader.newCommandLine(args, env);
    out = new StringWriter();
    err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
}
```

### 14.1 `MockClientCliSubcommandHelpTest`

- `mock-client --help` exit `0`, stdout contains `Commands:` and lists `run`, `launch-ice`, `launch-game`, `ice-smoke`.
- For each subcommand `<sub>`: `mock-client <sub> --help` exit `0`, stdout contains `Usage: mock-client <sub>` and the subcommand's description.
- `mock-client <sub> --help` does **not** trigger `Missing required options` even though required flags are absent (regression guard for the `--help` short-circuit).

### 14.2 `MockClientCliDispatchTest`

- Each subcommand, when invoked with `TestFixtures.minimalRequiredCli()` args plus the subcommand name, returns `ExitCodes.NOT_IMPLEMENTED`.
- Use a marker: each stub logs a unique string ("TODO: run not implemented yet"), and the test asserts that log line was produced. *Or* swap `LoggerFactory` for a recording sink — overkill; just assert via captured stdout.
- One test per subcommand. Parameterize if you prefer.

### 14.3 `MockClientCliUnknownSubcommandTest`

- `mock-client wat` returns `ExitCodes.USAGE` (`2`). Stderr contains `wat`. Stderr or stdout contains `Usage:`.
- Optional polish: if you wired the suggesting `IParameterExceptionHandler`, assert "did you mean" text.

### 14.4 `MockClientCliNoSubcommandTest`

- `mock-client` (empty `args`) returns `ExitCodes.USAGE`. Stdout contains `Usage:` and `Commands:`. **Do not** trigger `Missing required options` — the bare-root path prints help, it doesn't validate config.

### 14.5 `MockClientCliExitCodeTest`

- `mock-client --help` → `0`.
- `mock-client --version` → `0`.
- `mock-client run --help` → `0`.
- `mock-client wat` → `2`.
- `mock-client run --bogus=x` → `2`.
- `mock-client run` (no required flags) → `2`.
- `mock-client run …minimal-valid-config…` → `64`.

This is the single test class that codifies the table in §11.

### Existing tests — what to expect

- `ConfigLoaderHelpTest`, `ConfigLoaderInvalidValuesTest`, all `ConfigLoader*Test` — should pass unchanged. They drive `ConfigLoader.load(args, env)`, whose contract you preserved.
- `MockClientCliRecordSyncTest` — `counts()` test asserts 16 record components and 16 CLI options. If you accidentally add a flag, this test catches it.
- `LayeredDefaultProviderTest` — unchanged.

Run `./gradlew :mock-client:test` after every step; nothing should break.

---

## 15. Pitfalls (extended)

The short version was 7 items; this is the full list, in priority order.

### High priority

1. **`IllegalArgumentException` from `MockClientConfig` constructor.** Without translation in `toValidatedConfig(spec)`, the auth-channel error surfaces as a stack trace from `IExecutionExceptionHandler`. Centralise translation at the boundary; do not duplicate try/catch in every subcommand.

2. **`@ParentCommand` and visibility.** If subcommands live in `client.cli.*` and `MockClientCli` lives in `client.config.*`, then `MockClientCli` must be `public` and `toValidatedConfig` must be `public`. Either widen visibility deliberately or move the subcommand classes into the `config` package. The latter sidesteps the visibility issue but conflates "config" with "command tree."

3. **Default-value provider inheritance.** When `LayeredDefaultProvider` is registered on the root, picocli inherits it for subcommands. That's the intent. **Test it explicitly** with a `mock-client run` invocation that has only env vars set. Don't assume.

4. **`--config` position.** `preParseConfigFlag` walks the entire `args` array looking for `--config`. If a user writes `mock-client run --config foo.json`, the flag appears *after* the subcommand name. Picocli normally only accepts root flags before the subcommand name — verify that `--config` works in both positions. If picocli rejects post-subcommand placement, either accept that constraint and document it, or move `--config` to be a per-subcommand option (more duplication).

5. **`MockClientCliRecordSyncTest.counts()`.** Hard-coded to `16`. The four new subcommand classes don't add `@Option` fields, so the count stays at `16`. But if you add `--config` or `--log-level` as duplicates anywhere, this test will fail. Keep the count at 16 and the `CLI_ONLY_FIELD_NAMES` set correctly populated.

6. **Help on path triggering Logback.** `LoggingSetup.configure` opens log files. If `Main` calls it before `execute()`, every `--help` invocation produces a `logs/MockClient.jsonl` file. Move logging-init out of `Main` (it currently happens *after* `ConfigLoader.load` so it's already gated, but the gate disappears once `Main` becomes a one-liner). Put logging-init inside each subcommand's `call()`.

7. **`--version` empty.** `mixinStandardHelpOptions` only adds the `-V/--version` flag; the value comes from `@Command(version=…)`. The current root has no `version` attribute. Without it, `--version` prints an empty line. Add a constant; future-proof by reading from a Manifest attribute.

### Medium priority

8. **Stderr vs stdout for help.** `ConfigLoaderHelpTest` asserts on `System.out`. When `execute()` runs, picocli routes help to whatever you set with `cmd.setOut(...)` (defaults to `System.out`) — same as today. But error output goes to `cmd.setErr(...)` (defaults to `System.err`). New tests must capture both.

9. **`@Spec` injection.** Inside a `Callable.call()`, the cleanest way to get the `CommandSpec` (for printing usage from the call) is `@picocli.CommandLine.Spec CommandSpec spec;` — a field, not a parameter. Picocli auto-injects.

10. **Test isolation.** Picocli `CommandLine` instances are stateful (e.g., parsed values stick to the underlying user object). Build a fresh one per test (`@BeforeEach`). The existing tests already follow this pattern.

11. **Color output in CI.** `mixinStandardHelpOptions` enables ANSI colors by default if the terminal supports them. CI logs sometimes capture escape codes. Either set `CommandLine.Help.Ansi.OFF` in tests or assert with `String.contains` on substrings unaffected by colors (you're already doing the latter in `ConfigLoaderHelpTest`).

12. **JSON file precedence with subcommands.** The existing precedence test (`ConfigLoaderPrecedenceTest`) tests root-level invocations. Add at least one test that the precedence chain still works when invoked through a subcommand: e.g., file says port=8000, env says port=9000, CLI says port=10000 — when invoked as `mock-client run`, the subcommand sees `10000`.

### Low priority

13. **`@Command(versionProvider=…)`.** Once you have a release pipeline, switch to a `IVersionProvider` that reads from the JAR Manifest's `Implementation-Version`. For now a hardcoded string is fine.

14. **Unknown-subcommand suggestions.** Picocli has `UnmatchedArgumentException.printSuggestions(...)` which provides "did you mean X" output. Wire it via a custom `IParameterExceptionHandler` if you want. Optional polish.

15. **`scripts/`**. There's a top-level `scripts/` directory in the repo. After this change, consider whether a wrapper script (e.g., `scripts/mock-client`) helps developers — but probably not, since `./gradlew :mock-client:installDist` already produces a working binary. Don't create scripts speculatively.

---

## 16. Naming and style notes

Per `CONTRIBUTING.md` and the existing codebase:

- **Branch:** `feature/mock-client-cli-scaffold` (or similar, matching the prefix list).
- **Commit messages:** Conventional Commits with WBS id. E.g. `feat(mock-client): add Picocli subcommand scaffold [3.1.5.2]`.
- **Squash merge:** `git log main` should read as a WBS-indexed changelog. One squash per PR.
- **Spotless:** Google AOSP. `./gradlew :mock-client:spotlessApply` before pushing.
- **Checkstyle:** Google rules + suppressions. `./gradlew :mock-client:checkstyleMain checkstyleTest` must pass.
- **JavaDoc:** Per-class and per-public-method JavaDoc is mandatory (see `CONTRIBUTING.md`'s JavaDoc conventions, last updated commit `42334d1`). Stub subcommands need real (one-paragraph) JavaDoc explaining their eventual purpose, not just "TODO".
- **Package layout:** Recommended `client/cli/` for new types so the `config/` package stays focused on config-shaped types. Update `package-info.java`.

### Naming inside the new package

| Class | Name |
|---|---|
| Subcommand stub for `run` | `RunCommand` |
| Subcommand stub for `launch-ice` | `LaunchIceCommand` |
| Subcommand stub for `launch-game` | `LaunchGameCommand` |
| Subcommand stub for `ice-smoke` | `IceSmokeCommand` |
| Exit-code constants | `ExitCodes` |

The `*Command` suffix is a picocli convention and matches the pattern many picocli sample apps use. It also avoids clash with `MockClientCli` which is the *root* command.

---

## 17. Suggested commit shape

Per `CONTRIBUTING.md`, this is one squash-merged PR. Suggested intra-branch commits (squashed at merge):

1. `refactor(mock-client): extract CommandLine factory from ConfigLoader`
2. `feat(mock-client): introduce ExitCodes constants`
3. `feat(mock-client): add subcommand stubs (run, launch-ice, launch-game, ice-smoke)`
4. `refactor(mock-client): promote MockClientCli to root command, wire execute() in Main`
5. `test(mock-client): cover subcommand dispatch, help, unknown-subcommand, exit codes`
6. `docs(mock-client): document subcommands, global flags, exit codes`

Final PR title: `feat(mock-client): add Picocli subcommand scaffold [3.1.5.2]`

PR body: link issue #113, paste the invocation matrix from §3, link the new exit-code table from README.

---

## 18. Out of scope

These are explicitly **not** part of this ticket:

- Real lifecycle for any of `run`, `launch-ice`, `launch-game`, `ice-smoke`.
- Lobby WebSocket / OAuth flow.
- Subprocess launching of `faf-ice-adapter` or `mock-game` (`ProcessBuilder` work, `ProcessOutputLogger` integration).
- The mock-client lifecycle FSM (planned in `documentation/research/state-diagram.md`).
- GPGNet binary protocol (planned per `documentation/research/gpgnet-format-spec.md`, owned by mock-game).
- Per-subcommand flag subsets (every subcommand currently reuses the full root flag set).
- `--version` reading from JAR manifest (a constant string is fine for now).
- Multi-client orchestration (lives in the future top-level `faf-test-harness/` module).

Each of those is a sibling track that will replace its subcommand's stub when it lands.

---

## 19. Open questions to raise before merging

1. **Issue text references R32 and flag names that don't match the codebase** (`--oauth-refresh-token`, `--lobby-url`). The current schema has `--oauth-token-url`, `--oauth-access-token`, `--oauth-token-file`, `--lobby-websocket-url`. There is no refresh-token concept. Is R32 stale, is the issue informal, or is the schema due for a rename? **Do not silently change flags** — raise this as a separate ticket if R32 is binding.

2. **Env-var prefix discrepancy.** Issue notes use `MOCK_CLIENT_LOBBY_WEBSOCKET_URL`; actual prefix is `FAF_MOCK_CLIENT_…` (see `LayeredDefaultProvider.ENV_PREFIX`). Confirm which is canonical. The actual prefix is documented in `mock-client/README.md`; if R32 truly mandates a different prefix, that's a wider-impact change than this scaffolding ticket.

3. **Stub exit code: `0` or `64`?** Recommend `64`. Confirm with reviewers — once codified in tests it's harder to change.

4. **Should `mock-client` (no subcommand) print help or default to `run`?** Some CLIs default to a primary action when no subcommand is given. For a test harness, "fail loudly" (exit `2`, print help) is safer. Confirm.

5. **`subcommandsRequired = true`?** With this flag set, picocli auto-rejects bare invocations. Easier code; less customisable error message. Pick one.

6. **Custom `IParameterExceptionHandler` for "did you mean"?** Optional polish. Decide whether it's in scope for this ticket.

7. **`--version` source.** Hardcoded string for now, manifest later. Confirm.

Document the answers in the PR description so they're searchable later.
