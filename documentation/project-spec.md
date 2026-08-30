# FAForever Mocking Project

## A Little Background
FAForever is an open-source project emulating the multiplayer lobby of the video game "Supreme Commander: Forged Alliance". The project is working based on reverse engineering the original components and has replaced everything except of the "game" running on your computer itself.

## Problem Statement
The development of FAForever suffers on lacking standalone testing capabilities.
* To fully test the FAF client you need a working game and a working lobby server.
* To fully test the lobby server you need a working FAF client.
* To fully test network communication between the games via ICE adapter, you need both working games and working clients.

In order to address this problem we want to create headless CLI based mocks of:
* The FAF client and
* The Forged Alliance game

that behave exactly like the original component would. Extending it even further each of these components need to be able to switch going through their states from initializing, to lobby mode until a game is finished and so on.

## High-Level Architecture Diagram

## High-Level Architecture Diagram

```text
          +-------------------+
          | Lobby Server      |
          | (Auth, Matchmaking, |
          | Game Coordination)|
          +---------+---------+
                    ^
                    | JSON / REST / WebSocket
                    v
+-------------------+  IPC / RPC +-------------------+
| Local Client      |<---------->| Local Game        |
| (CLI Mockable)    |            | (CLI Mockable)    |
|                   |----------->|                   |
+---------+---------+ Launch /   +---------+---------+
          |           Control API          |
          |                                |
          | ICE Signaling                  | Game Traffic (UDP)
          v                                v
+---------+---------+            +---------+-------+
|   ICE Adapter     |<---------->| ICE Adapter     |
| (NAT Traversal,   |            | Other Player    |
| STUN,             |            |                 |
| P2P Negotiation)  |            |                 |
+---------+---------+            +---------+-------+
          ^                                ^
          |                                |
          | Signaling via Lobby Server     |
          +--------------------------------+
```

## Component Responsibilities

### 1. Lobby Server
* Authentication
* Matchmaking
* Game session orchestration
* ICE signaling coordination
* Does **not** carry actual game traffic

### 2. Local Client (Mock Target #1)
* Connects to lobby server
* Creates / joins games
* Receives game configuration
* Launches the local game process
* Communicates with ICE adapter
* Acts as orchestration layer

### 3. Local Game (Mock Target #2)
* Pure simulation engine
* Sends/receives UDP packets
* No awareness of lobby server
* Talks only to ICE adapter

### 4. ICE Adapter
* NAT traversal
* Peer discovery
* Establishes UDP P2P channels
* Bridges game traffic to remote peers

### 5. Other Players
* Symmetric architecture:
    * Client
    * Game
    * ICE adapter

---

## Suggested Problem Statement for Students

### Objective
Design and implement **CLI-based mock components** for:
1. A mock FAF client
2. A mock game process

These mocks should enable **headless integration testing** of:
* Lobby-server <-> client interaction
* Client <-> game lifecycle control
* Client <-> ICE adapter signaling
* Game <-> ICE adapter traffic exchange
* Full end-to-end multi-peer simulation

### Architectural Separation Requirements
Students should preserve strict boundaries:

| Component | Should Know About |
| :--- | :--- |
| **Lobby Server** | Clients only |
| **Client** | Lobby + Game + ICE |
| **Game** | Only ICE |
| **ICE Adapter** | Client (signaling) + Game (UDP) |

### Integration Testing Goals
Students should be able to:
* Run all components in separate processes
* Simulate 2-4 players locally
* Inject failures:
    * Delayed ICE negotiation
    * Dropped UDP packets
    * Game crash
* Validate system behavior via log inspection

### Advanced Extension Ideas
* Introduce state machines for each component
* Add reproducible deterministic simulation mode
* Create test harness that spawns N clients automatically
* Measure handshake completion time

## Related Resources
* Contact the FAF developers at https://faforever.zulipchat.com/
* Documentation of the network architecture: https://github.com/FAForever/faf-pioneer/blob/main/docs/network_architecture.md
* Documentation of the server-client protocol: https://github.com/FAForever/faf-api-specs
* GPGNet protocol docs: https://github.com/FAForever/faf-pioneer/blob/main/docs/gpgnet.md
* The FAF lobby server code: https://github.com/FAForever/server
* The FAF client code: https://github.com/FAForever/downlords-faf-client/
* Earlier approaches on mocking (unfinished) are found in this repo: https://github.com/Brutus5000/galactic-war
