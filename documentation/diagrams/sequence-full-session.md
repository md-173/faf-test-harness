# Full-session inter-component message flow

The complete inter-component message flow from authentication through to
teardown is split into three diagrams for readability. 

1. **Part 1 — Signalling & setup.** OAuth, lobby authentication, the
   canonical (matchmaker) game-setup path, local subprocess boot, and ICE
   negotiation.
2. **Game-setup variants.** A small companion diagram listing the three
   alternative pre-`game_launch` exchanges a real session can take.
3. **Part 2 — Gameplay & teardown.** Live UDP game traffic, end-of-session
   result reporting, heartbeat, and session close.

Internal component state machines are out of scope (see WBS 2.2.5). The
arrows in these diagrams are protocol messages crossing component boundaries,
not state transitions.

---

## Part 1 — Signalling & setup

Timeline ends the moment the local and peer ICE adapters have established a
UDP tunnel and the Mock Client is about to send `GameState("Launching")`.
The matchmaker setup flow is shown as the canonical path; custom-host and
custom-join variants are covered in the companion diagram below.

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Developer/CI
    participant MC as Mock Client
    participant MG as Mock Game
    participant IA as faf-ice-adapter
    participant Hydra as Ory Hydra
    participant LS as Lobby Server

    Note over Dev,Hydra: Phase 1 - Authentication
    Dev->>Hydra: HTTPS OAuth2 Authorization Code exchange
    Hydra-->>Dev: access_token (JWT)
    Dev->>MC: inject FAF_MOCK_ACCESS_TOKEN

    MC->>LS: WebSocket handshake (WSS, JSON over TCP)
    MC->>LS: ask_session
    LS-->>MC: session(N)
    MC->>LS: auth(token, unique_id, session)
    LS-->>MC: welcome
    LS-->>MC: player_info / game_info / social
    Note over MC,LS: On auth failure, server sends<br/>authentication_failed and closes.

    Note over MC,LS: Phase 2 - Game setup (matchmaker flow shown)
    MC->>LS: game_matchmaking(start)
    LS-->>MC: search_info(start)
    LS-->>MC: match_found
    LS-->>MC: game_launch
    Note over MC,LS: Custom-host and custom-join variants:<br/>see "Game-setup variants" diagram.

    Note over MC,IA: Phase 3 - Local subprocess boot
    MC->>IA: ProcessBuilder start + JSON-RPC init (TCP loopback)
    MC->>MG: ProcessBuilder start (CLI args from game_launch)
    MG->>IA: TCP connect (GPGNet framing)
    MG-->>MC: GameState("Idle") via local IPC
    MC->>LS: GameState("Idle") wrapped target:"game"
    MG->>IA: GameState("Lobby")
    MC->>LS: GameState("Lobby")
    LS-->>MC: HostGame / JoinGame / ConnectToPeer

    Note over MC,IA: Phase 4 - ICE negotiation (signalling via lobby only)
    MC->>IA: JSON-RPC setIceServers / connectToPeer
    IA-->>MC: JSON-RPC onIceMsg (local candidate)
    MC->>LS: IceMsg(target_id, blob) wrapped target:"game"
    LS-->>MC: IceMsg(sender_id, blob) from peer via server relay
    MC->>IA: JSON-RPC receiveIceMsg
    Note over IA: STUN/TURN discovery, candidate pair selection
    Note right of IA: Handoff to Part 2:<br/>UDP tunnel to peer open,<br/>GameState("Launching") next.
```

---

## Game-setup variants

The same `game_launch` payload in Part 1 can be triggered by three different
exchanges. Only one of these three blocks occurs in any given session.

```mermaid
sequenceDiagram
    autonumber
    participant MC as Mock Client
    participant LS as Lobby Server

    Note over MC,LS: Custom host
    MC->>LS: game_host(title, mod, mapname, visibility)
    LS-->>MC: game_launch

    Note over MC,LS: Custom join
    MC->>LS: game_join(uid)
    LS-->>MC: game_launch

    Note over MC,LS: Matchmaker
    MC->>LS: game_matchmaking(queue_name, start)
    LS-->>MC: search_info(start)
    LS-->>MC: match_found
    LS-->>MC: game_launch
```

Field-level payload reference for each variant lives in
[`../research/lobby-protocol-spec.md`](../research/lobby-protocol-spec.md) §4.

---

## Part 2 — Gameplay & teardown

Timeline begins at `GameState("Launching")` with the ICE tunnel already open.
The heartbeat loop runs continuously throughout the session (not only after
Phase 5); it is drawn here because Part 2 covers the longer wall-clock span
where it is most visible.

```mermaid
sequenceDiagram
    autonumber
    participant MC as Mock Client
    participant MG as Mock Game
    participant IA as faf-ice-adapter
    participant PIA as Peer ICE Adapter
    participant PMG as Peer Mock Game
    participant LS as Lobby Server

    Note over MC,LS: Continues from Part 1 - UDP tunnel already established

    Note over MG,PMG: Phase 5 - Gameplay
    MC->>LS: GameState("Launching")
    MG->>IA: GPGNet control commands (GameOption, PlayerOption, GameMods, ...)
    loop simulation ticks
        MG->>IA: UDP simulation packets
        IA->>PIA: UDP over NAT-traversed P2P tunnel
        PIA->>PMG: UDP simulation packets
        PMG->>PIA: UDP simulation packets (return path)
        PIA->>IA: UDP over NAT-traversed P2P tunnel
        IA->>MG: UDP simulation packets
    end
    Note over LS: Lobby carries NO game traffic during Phase 5

    Note over MC,LS: Phase 6 - End of session / result reporting
    MG->>IA: GameResult per army (GPGNet)
    MG->>IA: JsonStats (GPGNet)
    MG->>IA: GameEnded (GPGNet)
    MC->>LS: GameResult / JsonStats / GameEnded wrapped target:"game"
    Note over LS: Server persists results, updates ratings

    Note over MC,LS: Heartbeat runs throughout the session
    loop every PING_INTERVAL = 45s
        LS-->>MC: ping
        MC->>LS: pong
    end

    Note over MC,LS: Phase 7 - Teardown
    MC->>MG: terminate (SIGTERM then SIGKILL on timeout)
    MC->>IA: JSON-RPC quit then terminate subprocess
    MC-->>LS: WebSocket close (or kept open for next session)
```

---

## Reading guide

- **Participant-set changes between Part 1 and Part 2.** `Dev` and `Hydra`
  only matter for OAuth and are dropped from Part 2. The peer lane
  (`PIA`, `PMG`) only matters once the UDP tunnel is open, so it is dropped
  from Part 1. This is intentional and keeps each diagram narrow.
- **The `loop simulation ticks` block in Phase 5** is schematic. A real
  session exchanges thousands of UDP packets per second over the tunnel;
  the loop is drawn once for illustration.

## Source material

- OAuth, auth handshake, game setup, GPGNet wrapping, result reporting,
  heartbeat: [`../research/lobby-protocol-spec.md`](../research/lobby-protocol-spec.md)
  §§2–8.
- Component responsibilities and session narrative:
  [`../research/project-briefing.md`](../research/project-briefing.md)
  "How a Session Works" and "Communication Channels".
- Subprocess orchestration (ProcessBuilder, JSON-RPC init, teardown):
  [`../task-desc.md`](../task-desc.md) §1.1 Mock Client Core.