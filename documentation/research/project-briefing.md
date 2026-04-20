## Summary

FAForever is an open source community project that replicated the multiplayer 
functionality of the original game from scratch after the servers were shut down.

The game itself has never been modified. FAF works around it (the original binary 
executable), providing lobbies, matchmaking and player auth

---

## Components

### Lobby Server
The central hub. Handles player auth, maintains a list of online players and open 
games, coordinates starting new game sessions, and coordinates initial p2p networking 
setup between players. Does **not carry any game traffic data**. 

### FAF Client
The application a player runs on their machine. 
Connects to the lobby server, shows available games, lets the player host or join a game, and launches the game process at the right moment. 
Also manages the ICE adapter

### ICE Adapter
A networking tool that handles getting two players on different  networks to 
connect to each other. Home routers normally block incoming connections unless users set up port forwarding etc
The ICE adapter works around this using NAT traversal to establish a direct peer-to-peer link. The game talks through it.
We hopefully don't need to do too much with this.

### Game Process
The actual Supreme Commander executable. Has no knowledge of FAF client or the lobby. It opens a UDP port and waits to exchange game data with peers. 
From the game's perspective it is just playing a LAN game. The ICE adapter makes 
remote players look local.

---

## How a Session Works

**1 - Coming Online**

The player opens the FAF client. It connects to the lobby server and authenticates. 
The server sends back the current state of the world: online players and open games. 
The player is now visible to others.

**2 - Setting Up a Game**

A host creates a game lobby. The lobby server registers it and broadcasts it to all 
connected clients. Other players join. At this point everyone is still only connected 
to the lobby server. No direct connections between players exist yet.

**3 - Connecting Players**

When a game session is started, each client spawns its ICE adapter and then launches the game executable into a lobby state. The game communicates with the ICE adapter via GPGNet (CreateLobby, HostGame / JoinGame, ConnectToPeer). In parallel, the ICE adapters discover connection candidates via external STUN/TURN servers and relay those candidates to each other through the lobby server. This signaling does not carry actual game traffic. Once direct paths are established, the host starts the match and live game traffic begins flowing peer-to-peer through the ICE adapters.

**4 - Playing**

All game simulation happens on the players computers. Game data is exchanged directly 
between players thru the ICE adapter. When the game ends, the client sends the result back to the server for rating updates and shut down the ICE adapter.

---

## Communication Channels

| Channel             | Between                 | Protocol               | Carries                                                            |
| ------------------- | ----------------------- | ---------------------- | ------------------------------------------------------------------ |
| Lobby connection    | Client and Lobby Server | Line-delimited JSON over TCP (SimpleJsonProtocol; the server also supports a legacy QDataStream binary format)          | Auth, game listings, session setup, ICE candidate relay            |
| ICE adapter control | Client and ICE Adapter  | JSON-RPC over TCP      | Instructions: who to connect to, ICE candidates                    |
| GPGNet              | Game and ICE Adapter    | Custom binary over TCP | Game lifecycle events: lobby ready, game started, player connected |
| Game traffic        | Game and Game (via ICE) | UDP peer-to-peer       | Live game data: unit movement, commands, game state                |

---

## Problem

Testing any single component in isolation is currently  difficult.

| To test               | You need                                   |
| --------------------- | ------------------------------------------ |
| The lobby server      | A working FAF client                       |
| The FAF client        | A working lobby server and game executable |
| The ICE adapter       | A working client and a game                |
| End-to-end networking | All of the above, simultaneously           |

Nothing can be tested without everything else being present. The mock components 
fix this.They will use the real protocols, but their behaviour is controlled by tests rather than a human player or a real game engine.

---

## What We Are Building

**Mock FAF Client** - a Java program that looks exactly like the real FAF 
client to the lobby server and ICE adapter. It connects, authenticates, hosts or 
joins games, manages the ICE adapter, launches the mock game, and reports results 
back to the server. No UI. Fully scriptable.

**Mock Game Process** - a Java program that looks exactly like the real 
Supreme Commander game to the ICE adapter. It goes through the expected game 
lifecycle, produces  the expected network traffic, runs for a duration of our choosing, and 
exits. 

 The ICE adapter is not being mocked. The real faf-ice-adapter (the Java implementation currently deployed by FAF) will be used throughout. 

---

## Glossary

| Term          | Meaning                                                                                                            |
| ------------- | ------------------------------------------------------------------------------------------------------------------ |
| ICE Adapter   | The tool used to establish peer-to-peer connections across  routers                                                |
| NAT Traversal | Getting two machines on different home networks to connect directly                                                |
| GPGNet        | The binary protocol between the game and ICE adapter, (Gas Powered Games networking)                               |
| JSON-RPC      | A standard protocol for calling remote functions over a network connection, use between the client and ICE adapter |

