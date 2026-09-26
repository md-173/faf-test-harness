# Contributing to faf-test-harness

This document defines the team workflow for the FAF Test Harness project. All contributors must follow these conventions so `main` stays clean, reviewable, and stable.

## 1. Branching

All work happens on short-lived branches cut from the latest `main`. Never commit directly to `main`.

### Branch name format

```text
<type>/<wbs-id>-<short-kebab-description>
```

### Allowed types

| Prefix | Use for |
| :--- | :--- |
| `feature/` | New functionality tied to a WBS deliverable |
| `bugfix/` | Fix for an issue found during development |
| `hotfix/` | Urgent fix against `main` outside the normal sprint flow |
| `research/` | Spikes, prototypes, documentation-only research tasks |
| `chore/` | Tooling, build, dependency, or repo housekeeping |
| `docs/` | Documentation-only changes |

### Examples

- `feature/2.3.2-shared-module`
- `feature/1.1-lobby-comms`
- `bugfix/3.1-ice-adapter-teardown`
- `research/2.1-gpgnet-framing`
- `chore/2.3.4-ci-pipeline`

Rules:
- Lowercase, kebab-case, no spaces.
- WBS id is mandatory when the work maps to a WBS item.
- Keep the description under ~5 words.

## 2. Commits

We use [Conventional Commits](https://www.conventionalcommits.org/).

**Format:**

```text
<type>(<optional scope>): <description>

<optional body explaining the "why">

<optional footer, e.g. "BREAKING CHANGE: ...">
```

### Types

| Prefix | Use for |
| :--- | :--- |
| `feat` | A new feature for the application. |
| `fix` | A bug fix for a specific issue in the codebase. |
| `docs` | Documentation-only changes. |
| `style` | Changes that do not affect the meaning of the code. |
| `refactor` | A code change that neither fixes a bug nor adds a feature. |
| `test` | Adding missing tests or correcting existing test suites. |
| `build` | Changes that affect the build system or external dependencies. |
| `ci` | Changes to CI configuration files and automation scripts. |
| `chore` | Minor housekeeping changes that do not modify `src` or `test` files. |
| `revert` | Reverting a previously merged commit. |

### Scopes

Prefer a module name: `mock-client`, `mock-game`, `shared`, `ci`, `docker`, `docs`.

### Examples

- `feat(mock-client): add WebSocket auth handshake`
- `fix(mock-game): correct GPGNet frame length parsing`
- `docs: add CONTRIBUTING.md`
- `ci(gradle): enforce spotlessCheck on PRs`

Rules:
- Subject line ≤ 72 characters, imperative mood, no trailing period.
- One logical change per commit. Use `git rebase -i` to tidy up before pushing.

Note: individual commits on a feature branch do not require [<WBS-id>]. The PR title is the only subject that needs [<WBS-id>], because that's what becomes the squash commit on main.

## 3. Local Formatting and Verification

Code must be formatted and verified locally before pushing. CI will reject any PR that fails these checks.

From the repository root, run:

```bash
./gradlew spotlessApply check
```

What this does:

- `spotlessApply` — rewrites source files to Google Java Format (AOSP).
- `check` — runs the full Gradle verification lifecycle: compile, JUnit tests, Checkstyle, `spotlessCheck`, and the release asset-name assertion (see [Section 8](#8-releases)).

After the command completes, run `git status` / `git diff` so any formatter-driven changes are reviewed and committed intentionally.

Note that the `mock-client` `test` and `integrationTest` tasks run at `LOG_LEVEL=DEBUG` (set in `mock-client/build.gradle`), so a local run is noisier than the `INFO` default suggests. That is deliberate — see the `build` bullet below — and it applies to local runs as much as to CI.

The `mock-game` `test` task clamps the level instead (set in `mock-game/build.gradle`): `DEBUG` and `TRACE` pass through, anything higher becomes the `INFO` default. Several of its tests read the log and would otherwise fail, or pass without testing anything, in a shell that exported `LOG_LEVEL=WARN`.

### What CI runs on every PR

Three GitHub Actions jobs defined in `.github/workflows/ci.yml` run automatically on every pull request targeting `main`:

- **`build`** — runs `./gradlew build`, which compiles the code, executes unit tests, and enforces Checkstyle and `spotlessCheck`. This is the primary verification gate. It does **not** run `spotlessApply` — formatting drift causes CI to fail, not silently reformat. When it fails, the Gradle test reports are attached to the run's summary page as a `test-reports-<run-id>-<attempt>` artifact and kept for 14 days, so a failure can be diagnosed from the JUnit XML and HTML rather than the single assertion line in the log. The `mock-client` test task runs at `LOG_LEVEL=DEBUG` for the same reason: the lobby tests time out waiting for a frame often enough to matter, and at the default `INFO` neither `LobbyConnection`'s inbound-frame log nor the scripted server's send and receive lines are emitted, so the report cannot say whether a frame was late or never sent. Note that `build` stops at the first failing module, so the artifact holds that module plus any that finished before it — a green run uploads nothing at all.
- **`live-tests`** runs the four live tests that need no lobby and no FAF account, against the real `faf-ice-adapter` that `downloadIceAdapter` pins: `IceSmokeLiveTest`, `IceAdapterConnectionLiveSmokeTest`, `ClientGameLifecycleLiveTest` (which stands up its own scripted lobby) and mock-game's `GpgNetConnectionLiveSmokeTest`. It is the only check on a pull request that drives the real adapter, since `build` excludes the `integration` tag, and it reads no secret, so it runs on pull requests from forks too. Like the live integration workflow below, it sets `FAF_LIVE_REQUIRED` and checks the JUnit XML, so a missing jar or a renamed class fails it instead of letting it pass having run less. To reproduce it locally, run `./gradlew downloadIceAdapter` once, then the Gradle command from the job's `Run the live tests that need no lobby` step with `FAF_LIVE_REQUIRED=true` in the environment. Without that variable a missing jar skips all four and the run still looks green. It runs both modules' tests with `--continue`, so a mock-client failure does not hide the GPGNet result. On failure it uploads the reports and JSONL logs as `live-test-evidence-<run-id>-<attempt>`, kept for 14 days. The Release workflow runs the same job before it builds anything ([Section 8](#8-releases)). It was measured before it gated: 30 of 30 job runs passed on #458 (2026-09-24), in three rounds of ten legs, each taking 49 to 75 s beside `build`'s roughly 4 minutes. That still allows a flake rate of up to about 9.5% on those 30 alone, or 6.7% counting 13 earlier passes of the live integration workflow's `live-tests` (one-sided 95%), so if it goes red on two pull requests within 30 days for reasons unrelated to their change, it becomes advisory and the flake gets a fix card. Demoting it takes three changes: an admin removes it from the ruleset's required checks (no pull request can), it gains a job-level `continue-on-error`, and a note here exempts it from Section 4's all-green rule.
- **`dependency-submission`** — submits the project's dependency graph to GitHub so Dependabot can surface alerts on vulnerable (transitive) dependencies. It does not run tests or style checks.

`build`, `live-tests` and `dependency-submission` are required status checks on `main` ([Section 4](#4-pull-requests)): GitHub merges a PR only once each has passed on its latest commit. One that failed, was cancelled or has not reported yet blocks it, and one skipped by a job-level `if:` counts as passed.

### The live integration workflow (manual, advisory)

`.github/workflows/live-integration.yml` runs what `build` excludes: the `integration`-tagged tests that hit the live FAF test environment and launch the real `faf-ice-adapter`. It runs **only on manual dispatch** (`gh workflow run "Live integration (advisory)"`) and is **never a required check**, because the shared test lobby's availability is outside our control, so a failure there is a finding rather than a merge blocker. It is also the job a consumer (first: the java-ice-adapter maintainer) copies into their own CI.

- **`live-tests`** provisions the pinned adapter jar and runs the live tests that need no FAF account: `IceSmokeLiveTest`, `IceAdapterConnectionLiveSmokeTest`, `ClientGameLifecycleLiveTest`, `LobbyConnectionLiveSmokeTest.connectSucceeds` and mock-game's `GpgNetConnectionLiveSmokeTest`. It then checks the JUnit XML, because Gradle fails a `--tests` filter only when *no* pattern matched anything, so a renamed class would otherwise be skipped silently. All but `connectSucceeds` also run on every pull request, in `ci.yml`'s `live-tests` job; they stay here so that one dispatch covers every live test CI runs, on any ref.
- **`session`** runs `mock-client session --peers=2` from the release jars, one CI-only account per peer: the whole client, adapter and game path in a single verdict.

Two things are worth knowing before you touch it:

- **`FAF_LIVE_REQUIRED=true` turns a self-skip into a failure.** The live tests skip when a binary or credential is missing, which would let a misprovisioned CI run pass having tested nothing; under that variable each one fails instead, naming what it lacks. Both workflows set it. Leave it unset locally and `./gradlew integrationTest` keeps skipping exactly as before.
- **Credentials are per-peer and CI-only.** CI's accounts are never used by a local live run, because a second login as the same account signs the first out, fatally; the `concurrency` group is keyed on those accounts rather than on the branch for the same reason.
  - The job reads `FAF_CI_ACCESS_TOKEN_C` and `FAF_CI_ACCESS_TOKEN_D`, one pre-signed access token per peer, and passes each to `session --peer-access-token-file`. Nothing is exchanged at runtime, so the workflow passes no OAuth endpoint or client flag at all. Set them with `scripts/ci/mint-access-token.sh <refresh-token-file> <secret-name>` right before a dispatch; its `--dry-run` prints the token's subject, scopes and expiry without setting anything. Minting spends one Hydra rotation, but the script writes the rotated refresh token back to your file before it sets the secret, so the accounts do not need re-bootstrapping after a run the way they did when the runner consumed them. If a response ever carries no rotated token the script says so and leaves your file alone, which is the one case that still costs a bootstrap.
  - **Mint after the queue clears, not before.** A token is short-lived, and the script and the job both print its real expiry rather than leaving you to assume one. The `concurrency` group allows one running and one pending dispatch, so a run that waits behind another can outlive the token it was given. The job's first step decodes each token's `exp` and refuses an already-expired one in seconds, naming the secret and how long ago it lapsed; under fifteen minutes it warns and continues, since the session does not log in until after the build. One that expires mid-run still fails at the lobby, with the rejection in the evidence step.
  - Delete both secrets once a run is done. Nothing rotates them, so a secret left set is a bearer token sitting in the store until it lapses.

## 4. Pull Requests

1. Push your branch: `git push -u origin <branch-name>`.
2. Open a PR into `main` using the PR template (auto-loaded from `.github/PULL_REQUEST_TEMPLATE.md`).
3. Fill in every checklist item. Unchecked items = not ready for review.
4. Request review from at least **one** other team member (two for changes touching `shared/` or CI).
5. Address review comments with additional commits — do **not** force-push once review has started, except to rebase onto `main`.
6. A PR is mergeable when:
   - All CI checks are green.
   - At least one approving review from a teammate other than the author.
   - No unresolved review comments.
   - The branch is up to date with `main`.

### Merge strategy

`main` uses **Squash and merge**. The PR title is used as the squash commit subject, so it must follow Conventional Commits **and include the WBS id in square brackets**:

```text
<type>(<optional scope>): <description> [<WBS-id>]
```

Examples:

- `feat(shared): add message codec [2.3.1]`
- `fix(mock-game): correct GPGNet frame length parsing [3.1]`
- `docs: amend CONTRIBUTING.md merge strategy [2.3.1]`

After squash-merge this lands on `main` as e.g. `feat(shared): add message codec [2.3.1] (#123)`.

Rationale: one WBS item → one PR → one commit on `main`. The `[<WBS-id>]` suffix survives the squash (the branch name never reaches `main`), keeping `git log main` readable as a WBS-indexed changelog and making `git bisect` trivial.

### Branch protection on `main`

A repository ruleset ("Force Pull Requests") is what GitHub enforces on `main`:

- Changes arrive only through a pull request, with one approving review and every review thread resolved.
- Squash merges only.
- The required status checks `build`, `live-tests` and `dependency-submission`, from GitHub Actions.
- No force pushes, and `main` cannot be deleted.
- Nobody can bypass these rules, admins included.

The rest of the merge rules above (item 6) are the reviewers' to uphold. GitHub does not require a branch to be up to date with `main` before it merges, so rebase first ([Section 5](#5-keeping-your-branch-current)), and it does not delete a merged branch.

## 5. Keeping your branch current

Keep your feature branch rebased on the latest `main` so your PR can merge cleanly (see Section 4 — "branch is up to date with `main`" is a merge requirement). From your feature branch:

```bash
git fetch origin
git rebase origin/main
```
Shorthand: `git pull --rebase origin main`

If you've already pushed your feature branch and need to rewrite its history (rebase, amend, squash), use `git push --force-with-lease` on the feature branch. Never force-push to main or any protected branch.

```bash
git push --force-with-lease
```

## 6. JavaDocs Conventions

Required on all public and protected types and members. Optional (but encouraged where non-obvious) on package-private and private members. Not required on @Override methods, trivial getters/setters, or test methods.

Important: The first sentence of your JavaDoc (up to the first period followed by whitespace) is extracted by the compiler to create the summary tables. This first sentence must be a high-level overview.


<b>Class Level Template</b>
```java
/**
 * [Short, one-sentence summary of the class or interface's primary purpose].
 *
 * <p>[Detailed description of how the class works, its main responibilities,
 * and any important concepts. Use multiple paragraphs if you need to 
 * explain specific behaviours]
 *
 * <p>[Optional: Detail specific mechanics]
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Add a concise, realistic code example demonstrating the primary use case
 * ExampleClass instance = new ExampleClass("arguments");
 * instance.doSomething();
 * }</pre>
 *
 * @author Name1
 * @author Name2
 * @see [RelatedClassOrInterface]
 */
public class ExampleClass {
    // ...
}
```
<b>Method Level Template</b>
```java
/**
 * [Short, third-person, active-voice description of what the method does].
 *
 * <p>[Detailed explanation of the method's behavior, state changes, or specific 
 * algorithms used. Mention if it runs synchronously or asynchronously.]
 *
 * @param paramName [Description of the parameter, including valid values or constraints]
 * @param paramName  [Description of another parameter]
 * @return [Description of the return value]
 * @throws IllegalArgumentException if [Condition under which the exception is thrown]
 * @throws IllegalStateException    if [Condition]
 */
public Object doSomething(String paramName, int paramName) {
    // ...
}
```

### Additional rules

**Scope**: Applies to classes, interfaces, enums, records, and annotation types. For records, document each component with @param on the class-level Javadoc. package-info.java carries a package-level summary.  
**Nullability**: Parameters and return values are non-null by default. Explicitly document any that may be null on @param / @return. Prefer a nullness annotation (e.g. JSpecify) when available.  
**Exceptions**: Every checked exception in throws needs a matching @throws. Document unchecked exceptions that are part of the contract (e.g. IllegalArgumentException on bad input).  
**Overrides**: Omit the Javadoc to inherit the parent's doc verbatim. Only write a block when supplementing it, and use {@inheritDoc} where the parent's text should appear.  
**Void methods**: No @return tag.  
**Linking**: Use {@link ClassName#method(ParamType)} to reference code (include param types for overloaded methods) and {@code ...} for inline code snippets.  

## 7. Logging

All components use a shared structured logging framework built on SLF4J + Logback.

### Setup in a new component

Call `LoggingSetup.configure(componentName)` once in a static initialiser, before any logger is obtained. 

```java
import com.faforever.testharness.shared.logging.LoggingSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    // must run before the LOG field so Logback picks up LOG_FILE on first init 
    static {
        LoggingSetup.configure("MyComponent"); // must be first
    }
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(final String[] args) {
        LOG.info("Started");
    }
}
```

### Obtaining a logger in any class

```java
private static final Logger LOG = LoggerFactory.getLogger(MyClass.class);
```

Never pass loggers as arguments or store them as instance fields — the static pattern is sufficient and safe.

### Capturing subprocess output

Wrap any child process with `ProcessOutputLogger.captureAsync` immediately after starting it:

```java
Process ice = new ProcessBuilder("faf-ice-adapter", "--args").start();
ExecutorService readers = ProcessOutputLogger.captureAsync(ice, "ICEAdapter");
ice.waitFor();
readers.shutdown();
```

Every stdout/stderr line is then logged at INFO (stdout) or WARN (stderr) and tagged `[ICEAdapter]` in both console and JSONL output. Consecutive stack-trace lines (starting with a tab or `Caused by:`) are merged into a single log event.

### Output formats

| Output                                                    | Format                                                                                          | Purpose |
|:----------------------------------------------------------|:------------------------------------------------------------------------------------------------| :--- |
| Console (stdout)                                          | `[2026-04-17 12:00:00.000] [MockClient] [INFO ] Connected.`                                     | Human-readable during development |
| File (`logs/<component>.jsonl;` `LOG_FILE env`/`-D` overrides) | `{"timestamp":"…","component":"MockClient","level":"INFO","logger":"…","message":"Connected."}` | Programmatic parsing by the test suite |

When `INSTANCE_NAME` is set, both formats carry the label: the console renders
`[MockClient] [peer-a]` and the JSONL record gains an `"instance":"peer-a"`
field after `component`. Leaving it unset omits the field entirely, so
single-instance output is unchanged.

### Configuration

| Variable | Default                  | Description |
| :--- |:-------------------------| :--- |
| `LOG_LEVEL` | `INFO` | Minimum level for all loggers (`DEBUG`, `INFO`, `WARN`, `ERROR`), for components that do not configure one themselves — see below. |
| `LOG_FILE` | `logs/<component>.jsonl` | JSONL output file path |
| `INSTANCE_NAME` | unset                    | Labels one of several concurrent instances of a component. Pairs with `LOG_FILE`; see `mock-client/README.md` § "Harness log contract". Set it as an environment variable so subprocesses inherit it. |

**On `LOG_LEVEL` specifically.** Logback resolves the *system property* of that name ahead of the
environment variable, and `mock-client` writes its resolved `--log-level` — including the built-in
default of `INFO` — into that property before the first logger exists. So a bare `LOG_LEVEL` does
not raise `mock-client`'s level; use `--log-level` or `FAF_MOCK_CLIENT_LOG_LEVEL`. `mock-game` has
no such flag and honours the variable **when run directly** — but not under `mock-client run`,
because `MockGameLauncher` and `IceAdapterLauncher` both overwrite `LOG_LEVEL` in the children they
spawn with `mock-client`'s own resolved level. An orchestrated run therefore comes up at
`mock-client`'s level in all three processes, the adapter included only when it is launched from
a `.jar`: upstream reads no `LOG_LEVEL`, so the level reaches it through the headless logback
config the launcher injects on that path alone (`subprocess-orchestration-spec.md` §2.3).


## 8. Releases

A release is cut by hand from the `Release` workflow (`.github/workflows/release.yml`). It builds a
shadow jar per mock, writes a `.sha256` beside each, and opens a **draft** release carrying all four
assets.

### Cutting one

1. **Prove the live path on the branch you are about to cut.** Actions → Live integration
   (advisory) → Run workflow, with "Use workflow from" set to that branch, and the two
   access-token secrets minted beforehand and deleted after (§ 3). `workflow_dispatch` takes a
   branch or a tag, never a bare commit, so confirm the tip is still the commit you mean and
   re-dispatch if it moves before step 2. The gate in step 3 runs `check` and the four lobby-free
   live tests, none of which reach the lobby, so nothing else in this procedure says whether the
   client, adapter and game still complete a session against the real lobby. It builds its own
   snapshot jars from source
   rather than the release assets, so it does not replace the draft checks in step 4. Green alone is
   not the verdict: the evidence step must emit no warning, which is what shows two `faf-uid` lines
   and two distinct logins. A red run stops the cut until you know which kind it is: the shared lobby
   being unavailable is a finding about the environment, a failed checkpoint is a finding about the
   release.
2. **Run the workflow from `main`.** Actions → Release → Run workflow, with "Use workflow from" set
   to `main`. Give the version with no leading `v` (`0.3.0`, not `v0.3.0`), and leave `prerelease`
   unticked unless the release is genuinely one. The input is not validated: whatever is typed
   becomes the tag and the version segment of both jar names.
   Releasing from a branch is a convention, not an enforced rule, and it cannot be enforced in this
   file: `workflow_dispatch` runs the copy of `release.yml` on the branch selected, so a branch whose
   copy predates a change simply runs the older workflow, gate and all.
3. **The workflow verifies before it builds.** Before any jar is built it runs `./gradlew
   -Pversion=<version> check`, the verification `ci.yml`'s `build` job applies to every pull
   request (`check` is the verification half of `build`), and, in a read-only `live-tests` job the
   release job waits on, the four lobby-free live tests that `ci.yml`'s `live-tests` job runs. A
   release is dispatched at an arbitrary commit and nothing else guarantees CI ran green on it. If
   either fails, no draft and no assets are created, so re-running is safe: nothing was tagged or
   published. A red gate is not to be worked around. If it is a known flake rather than a real
   failure (the lobby tests occasionally time out waiting for a frame, see § 3; the live tests'
   first-frame race, see the triage note in `ci.yml`), re-run the failed jobs and let them pass on
   their own; a skipped release job re-runs with them. Both jobs upload their evidence on failure,
   as `ci.yml` does.
4. **Check the draft before publishing.** It must carry exactly four assets:
   `mock-client-<version>-all.jar`, `mock-game-<version>-all.jar`, and a `.sha256` for each. Download
   them and confirm the checksums:

   ```bash
   gh release download <version> --dir /tmp/release-check --clobber
   ( cd /tmp/release-check && sha256sum -c ./*.sha256 )
   ```

   If the workflow has to be re-run after a run that *succeeded*, delete the draft first. The release
   step does not pass `allowUpdates`, so it fails while a release already exists for the tag.

5. **Publish the draft.** `GET /releases/latest` skips drafts and prereleases alike, so until someone
   opens the draft and clicks Publish, a consumer following that route keeps getting the previous
   release. Publishing is the last step of every release, not an optional one.
6. **Bump the version in the docs** if the release is referenced by number. `README.md` no longer
   names jar files by version: it writes `<version>` and pulls from `releases/latest`, so it needs
   no bump. Check anything that does name a number.

### The asset names are a contract

`mock-client-<version>-all.jar` and `mock-game-<version>-all.jar` are what a downstream pipeline
greps for after pulling `releases/latest` (`documentation/operations/harness-runbook.md` § 2a).
Renaming either breaks that build with no commit on the consumer's side, so `check` asserts both
names through `verifyReleaseAssetName` in the root `build.gradle`, and a rename fails the pull
request that makes it. Changing the names is a decision to take with the consumer first, and it then
has to be applied to that task, the names in `release.yml`, and the runbook together.

### Checksums on earlier releases

0.1.0 and 0.2.0 carry no `.sha256` files, and they are deliberately not being backfilled (this
supersedes that item in #317). The documented consumer route pulls `releases/latest` unpinned and
never names a version, so the checksums matter for whichever release is current, not for older ones.
A checksum generated after the fact from an already-published asset also proves nothing the GitHub
API's own per-asset `digest` does not. Releases from 0.3.0 onward ship them.

### Pinning workflow actions

- GitHub's own `actions/*` float on major tags (`actions/checkout@v7`). They are first-party, and the
  majors are bumped deliberately rather than automatically, as in #346.
- Every third-party action is pinned to a full commit SHA with the version in a trailing comment:

  ```yaml
  uses: ncipollo/release-action@339a81892b84b4eeb0f6e744e4574d79d0d9b8dd # v1.21.0
  ```

  A tag can be moved by whoever owns the action, and the release job holds `contents: write`, so a
  moved tag there would run unreviewed code with permission to write releases and tags. The SHA is
  what is verified; the comment is what makes the line readable. Bumping one is a normal pull
  request: update both the SHA and the comment, and say why in the body, as #346 did when it held
  `gradle/actions` at v5.

## 9. When in doubt

Ask in the team channel before inventing a new convention. Amendments to this document go through a normal PR and must be approved by the team lead.