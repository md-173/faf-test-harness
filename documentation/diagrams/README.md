# Component Overview Diagrams

High-level visual blueprints of the FAF Test Harness. These diagrams are the
source of truth for component boundaries, transport protocols, and the
inter-component message flow of a full game session.

Refs: WBS 2.2.4 — FAF Component Overview Diagrams.

## Contents

| Diagram | File | Purpose |
|---|---|---|
| Architecture & data flow | [`architecture.md`](./architecture.md) | Component boundaries, mock vs real, transport protocol on every edge. |
| Sequence — full session | [`sequence-full-session.md`](./sequence-full-session.md) | Message-level timeline from authentication through teardown. Split into two parts with a small companion diagram for the game-setup variants. |

## Conventions

- **Format.** All diagrams are authored in [Mermaid](https://mermaid.js.org/)
  so they render inline on GitHub and diff cleanly under version control.
- **Protocol labels.** Transport names match the Communication Channels table
  in [`../research/project-briefing.md`](../research/project-briefing.md)
  verbatim. If a label here disagrees with the briefing, the briefing wins and
  this folder should be updated.
- **Mock vs real.** `[MOCK]` suffix and the orange fill indicate components
  authored in this repo. `[REAL]` suffix and the blue fill indicate components
  from upstream FAForever that we reuse (notably `faf-ice-adapter` and the
  FAF Lobby Server). The Ory Hydra OAuth2 provider is coloured separately as
  an external service the harness depends on but does not orchestrate.
- **Scope.** These diagrams describe external, inter-component messaging only.
  Internal component state machines (e.g. the Mock Client FSM) are out of
  scope and will be defined by WBS 2.2.5.

## Related research

Detailed protocol payloads and field-level references live alongside these
diagrams:

- [`../research/project-briefing.md`](../research/project-briefing.md) —
  one-page overview, communication-channel summary, and glossary.
- [`../research/lobby-protocol-spec.md`](../research/lobby-protocol-spec.md) —
  full lobby-protocol reference: OAuth, WebSocket framing, auth handshake,
  game setup, GPGNet-over-WebSocket wrapping, result reporting, heartbeat.
- [`../project-spec.md`](../project-spec.md) — original problem statement and
  upstream FAForever resource links.

## Maintenance

- Update these diagrams whenever a new protocol link is added between
  components, or when the mock vs real split changes (e.g. if we ever mock
  the ICE adapter).
- Avoid adding component-internal state transitions here — those belong in
  the FSM deliverable (WBS 2.2.5).
- When editing, paste the Mermaid block into <https://mermaid.live> before
  committing to confirm it renders. GitHub's Mermaid build is stricter about
  whitespace than Mermaid Live, so keep single-space separation between
  structural tokens on edge lines (`A -->|"label"| B`) and avoid column
  alignment in the diagram body.