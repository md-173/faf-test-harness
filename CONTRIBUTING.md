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
- `check` — runs the full Gradle verification lifecycle: compile, JUnit tests, Checkstyle, and `spotlessCheck`.

After the command completes, run `git status` / `git diff` so any formatter-driven changes are reviewed and committed intentionally.

### What CI runs on every PR

Two GitHub Actions jobs defined in `.github/workflows/ci.yml` run automatically on every pull request targeting `main`:

- **`build`** — runs `./gradlew build`, which compiles the code, executes unit tests, and enforces Checkstyle and `spotlessCheck`. This is the primary verification gate. It does **not** run `spotlessApply` — formatting drift causes CI to fail, not silently reformat. When it fails, the Gradle test reports are attached to the run's summary page as a `test-reports-<run-id>-<attempt>` artifact and kept for 14 days, so a failure can be diagnosed from the JUnit XML and HTML rather than the single assertion line in the log. Note that `build` stops at the first failing module, so the artifact holds that module plus any that finished before it — a green run uploads nothing at all.
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

`main` uses **Squash and merge**. The PR title is used as the squash commit subject, so it must follow Conventional Commits **and include the WBS id in square brackets**:

```text
<type>(<optional scope>): <description> [<WBS-id>]
```

Examples:

- `feat(shared): add message codec [2.3.1]`
- `fix(mock-game): correct GPGNet frame length parsing [3.1]`
- `docs: amend CONTRIBUTING.md merge strategy [2.3.1]`

After squash-merge this lands on `main` as e.g. `feat(shared): add message codec [2.3.1] (#123)`.

Rationale: one WBS item → one PR → one commit on `main`. The `[<WBS-id>]` suffix survives the squash (branch names are deleted on merge), keeping `git log main` readable as a WBS-indexed changelog and making `git bisect` trivial.

### Branch protection on `main`

- Require pull request before merging.
- Require status checks (`build`, `dependency-submission`) to pass.
- Require branches to be up to date before merging.
- Disallow force pushes and direct pushes.
- Delete head branch after merge.

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
| `LOG_LEVEL` | `INFO`                   | Minimum level for all loggers (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `LOG_FILE` | `logs/<component>.jsonl` | JSONL output file path |
| `INSTANCE_NAME` | unset                    | Labels one of several concurrent instances of a component. Pairs with `LOG_FILE`; see `mock-client/README.md` § "Harness log contract". Set it as an environment variable so subprocesses inherit it. |


## 8. When in doubt

Ask in the team channel before inventing a new convention. Amendments to this document go through a normal PR and must be approved by the team lead.