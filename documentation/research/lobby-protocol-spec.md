## 1. Transport Layer

### Protocol
The FAF Lobby Server uses a **WebSocket-based bidirectional messaging protocol**. All messages are JSON objects separated by newlines, with a `command` field identifying the message type.

### Server Endpoints

| Environment | URL | Protocol |
|---|---|---|
| Production | `wss://ws.faforever.com/ws` | WSS (TLS) |
| Test | `wss://ws.faforever.xyz/ws` | WSS (TLS) |
| Local | `ws://localhost/ws` | WS |

### Wire Format
Each message is a single JSON object terminated by a newline character (`\n`). There is no additional framing beyond WebSocket frames themselves.

```json
{"command": "ping"}\n
```

Every message contains a `command` field that identifies the message type. Direction is implicit: some commands are only sent by the client, some only by the server, and some (like `ping`/`pong`) are sent by both.

### Architecture Note
The WebSocket endpoint is not served directly by the lobby server. The lobby server uses a raw TCP protocol internally ([SimpleJsonProtocol](https://faforever.github.io/server/protocol/simple_json.html)). A separate bridge service, [ws_bridge_rs](https://github.com/FAForever/ws_bridge_rs), translates between WebSocket and the server's internal TCP protocol.

The Mock Client connects via **WebSocket only** and does not need to implement the raw TCP protocol.

### Connection Lifecycle
1. Client opens a WebSocket connection to one of the server endpoints
2. Connection is upgraded via standard WebSocket handshake
3. Once connected, client and server exchange newline-delimited JSON messages
4. Connection is maintained via periodic ping/pong heartbeats (see [Section 8](#8-heartbeat--keep-alive))
5. Connection terminates when either side closes the WebSocket or the session times out

### Sources
- [FAForever Lobby Server AsyncAPI Spec](https://faforever.github.io/faf-api-specs)
- [SimpleJsonProtocol (internal server protocol)](https://faforever.github.io/server/protocol/simple_json.html)
- [ws_bridge_rs (WebSocket bridge)](https://github.com/FAForever/ws_bridge_rs)