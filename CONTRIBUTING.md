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

<optional footer, e.g. "Refs: WBS-12" or "BREAKING CHANGE: ...">
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
- Reference the WBS issue in the body or footer when applicable (`Refs: WBS-2.3.1`).

## 3. Local Formatting and Verification

Code must be formatted and verified locally before pushing. CI will reject any PR that fails these checks.

From the repository root, run:

```bash
./gradlew spotlessApply check
```

What this does:

- `spotlessApply` — rewrites source files to Google Java Format (AOSP).
- `check` — runs the full Gradle verification lifecycle: compile, JUnit tests, Checkstyle, and `spotlessCheck`.

After the command completes, run `git status` / `git diff` so any formatter-driven changes are reviewed and committed intentionally.

### What CI runs on every PR

Two GitHub Actions jobs defined in `.github/workflows/ci.yml` run automatically on every pull request targeting `main`:

- **`build`** — runs `./gradlew build`, which compiles the code, executes unit tests, and enforces Checkstyle and `spotlessCheck`. This is the primary verification gate. It does **not** run `spotlessApply` — formatting drift causes CI to fail, not silently reformat.
- **`dependency-submission`** — submits the project's dependency graph to GitHub so Dependabot can surface alerts on vulnerable (transitive) dependencies. It does not run tests or style checks.

Both jobs are listed as required status checks on `main` (see [Section 4](#4-pull-requests)). If either fails or is skipped, the PR cannot be merged.

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

`main` uses **Squash and merge**. The squash commit message must itself follow Conventional Commits (the PR title is used as the squash subject, so write the PR title accordingly).

Rationale: one WBS item → one PR → one commit on `main`. Keeps `git log main` readable as a project changelog and makes `git bisect` trivial.

### Branch protection on `main`

- Require pull request before merging.
- Require status checks (`build`, `dependency-submission`) to pass.
- Require branches to be up to date before merging.
- Disallow force pushes and direct pushes.
- Delete head branch after merge.

## 5. Keeping your branch current

Prefer rebase over merge commits while a branch is in progress:

```bash
git fetch origin
git rebase origin/main
```

If you have already pushed, force-push with lease:

```bash
git push --force-with-lease
```

## 6. Logging

All components use a shared structured logging framework built on SLF4J + Logback.

### Setup in a new component

Call `LoggingSetup.configure(componentName)` once, before any logger is obtained, at the top of `main()`:

```java
import com.faforever.testharness.shared.logging.LoggingSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(final String[] args) {
        LoggingSetup.configure("MyComponent"); // must be first
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

| Output | Format | Purpose |
| :--- | :--- | :--- |
| Console (stderr) | `[2026-04-17 12:00:00] [MockClient] [INFO ] Connected.` | Human-readable during development |
| File (`logs/test-harness.jsonl`) | `{"timestamp":"…","component":"MockClient","level":"INFO","logger":"…","message":"Connected."}` | Programmatic parsing by the test suite |

### Configuration

| Variable | Default | Description |
| :--- | :--- | :--- |
| `LOG_LEVEL` | `INFO` | Minimum level for all loggers (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `LOG_FILE` | `logs/test-harness.jsonl` | JSONL output file path |

Use a separate `LOG_FILE` per process when running multiple components concurrently to avoid interleaved writes:

```bash
LOG_FILE=logs/mock-client.jsonl ./gradlew :mock-client:run &
LOG_FILE=logs/mock-game.jsonl   ./gradlew :mock-game:run &
```

## 7. When in doubt

Ask in the team channel before inventing a new convention. Amendments to this document go through a normal PR and must be approved by the team lead.