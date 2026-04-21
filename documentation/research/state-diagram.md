# State Diagram

These are the states and transitions for the mock game and mock client.
These are largely based on the diagrams found in https://github.com/faforever/faf-api-specs

Note that the diagrams on that page are from the point of view of the server, and therefore do not present a complete picture of how each component behaves.
Also of note, most/all information on those pages was AI generated and has not been double-checked by FAF developers.
We have confirmed all information against the actual FAF server code.

In this document (and in FAF development in general) `CamelCase` messages represent GPGNet commands, while `snake_case` messages represent lobby messages.

## Game State Machine

The mock game has 6 states:
| State        | Description                         |
|--------------|-------------------------------------|
| INITIALIZING | Connecting to the server            |
| IDLE         | Waiting on instructions             |
| HOSTING      | Hosting a game, waiting for players |
| JOINING      | Joining a game                      |
| LIVE         | Game simulation running             |
| ENDED        | Ended                               |


```mermaid
stateDiagram-v2
    [*] --> INITIALIZING
    INITIALIZING --> IDLE : Connection to the GPGNet server established / Send GameState(Idle) message
    IDLE --> HOSTING : HostGame message from server
    IDLE --> JOINING : JoinGame message from server
    HOSTING --> LIVE : Send GameState(Launching) message
    JOINING --> LIVE : TODO
    LIVE --> ENDED : Game finished / Send GameState(Ended) message
    ENDED --> [*]
```
*State diagram of the mock game*

## Client State Machine

The mock client has 6 states:
| State         | Description                                                 |
|---------------|-------------------------------------------------------------|
| INITIALIZING  | Connecting to the server                                    |
| IDLE          | Waiting for player input (to join or start a game)          |
| STARTING_GAME | Opening game binary, establising necessary connections      |
| HOSTING       | Hosting a game, waiting for players/for user to launch game |
| JOINING       | Join an existing game                                       |
| PLAYING       | Game simulation running                                     |

There are two additional states considered by the server: SEARCHING_LADDER and STARTING_AUTOMATCH.
These are used for ladder/matchmaking games (as opposed to custom games).
While adding this is a feature worth considering, it is not a priority at this moment.

```mermaid
stateDiagram-v2
    [*] --> INITIALIZING
    INITIALIZING --> IDLE: Connection established with server

    IDLE : IDLE
    IDLE : Send game_host command if hosting
    IDLE : Send game_join command if joining

    IDLE --> STARTING_GAME: game_launch command from server

    STARTING_GAME : STARTING_GAME
    STARTING_GAME : Send GameState(IDLE) and GameState(LOBBY) messages

    STARTING_GAME --> HOSTING : HostGame message from server
    STARTING_GAME --> JOINING : JoinGame message from server
    JOINING --> JOINING : ConnectToPeer messages from server
    HOSTING --> PLAYING : Send GameState(LAUNCHING) message
    JOINING --> PLAYING : TODO
    PLAYING --> [*]
```

