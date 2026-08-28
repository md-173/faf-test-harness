# faf-test-harness
A CLI test harness utility intended for Forged Alliance Forever

## Local setup
- Contributor workflow & conventions: [CONTRIBUTING.md](CONTRIBUTING.md)
- Setup and single-session runbook, for embedding the mock game in another
  project's tests or running a full client session against the live lobby:
  [documentation/operations/harness-runbook.md](documentation/operations/harness-runbook.md)
- Provision the real ICE adapter the Mock Client drives (`./gradlew downloadIceAdapter`):
  [documentation/operations/ice-adapter-setup.md](documentation/operations/ice-adapter-setup.md)
