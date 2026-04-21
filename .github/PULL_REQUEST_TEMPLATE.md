## Summary
<!-- One or two sentences describing *what* changed and *why*. -->
## Related WBS / Issue
Closes #<issue-number>
Refs: WBS-x.x
## Type of change
- [ ] feat — new feature
- [ ] fix — bug fix
- [ ] refactor — no behavior change
- [ ] docs — documentation only
- [ ] test — adds or updates tests
- [ ] build / ci / chore — tooling or infra
- [ ] research — spike or investigation
- [ ] breaking — requires downstream changes (also add `BREAKING CHANGE:` in the commit footer)
## Changes
<!-- Bullet list of notable changes. -->
-
-
## How was this tested?
<!-- Commands run, scenarios covered, screenshots/logs if useful. -->
- [ ] Unit tests added or updated
- [ ] Manual verification described below
## Pre-merge checklist
- [ ] Branch name follows `CONTRIBUTING.md` (`type/wbs-id-description`)
- [ ] Commits follow Conventional Commits
- [ ] `./gradlew spotlessApply check` passes locally (no formatting diff, Checkstyle + tests green)
- [ ] PR title is written as a Conventional Commit (used as the squash commit message)
- [ ] Branch is rebased on the latest `origin/main`
- [ ] Self-review of the diff completed
- [ ] No commented-out code, debug prints, or TODOs without a tracking issue
- [ ] Documentation updated if behavior, build, or workflow changed
- [ ] Security: inputs crossing process boundaries (`ProcessBuilder`, JSON-RPC, sockets) are validated/sanitized — or N/A
- [ ] No secrets, credentials, or environment-specific paths committed
## Reviewer notes
<!-- Risk, tradeoffs, architectural implications, or follow-ups reviewers should focus on. -->