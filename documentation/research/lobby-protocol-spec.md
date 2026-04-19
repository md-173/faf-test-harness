## 1. WebSocket Transport Layer

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

> **Implementation note:** In the raw TCP protocol, the newline is strictly required as the message delimiter. Over WebSocket, framing is handled natively by the protocol itself (each send/receive is a discrete frame). The `\n` may still be present as a pass-through artifact from the bridge. Implementations should include the trailing `\n` in outgoing messages for safety, but should not rely on it for parsing incoming messages — use WebSocket frame boundaries instead.

```json
{"command": "ping"}\n
```

Every message contains a `command` field that identifies the message type. Direction is implicit: some commands are only sent by the client, some only by the server, and some (like `ping`/`pong`) are sent by both.

### Architecture Note
The WebSocket endpoint is not served directly by the lobby server. The lobby server uses a raw TCP protocol internally ([SimpleJsonProtocol](https://faforever.github.io/server/protocol/simple_json.html)). A separate bridge service, [ws_bridge_rs](https://github.com/FAForever/ws_bridge_rs), translates between WebSocket and the server's internal TCP protocol.

The Mock Client connects via **WebSocket only** and does not need to implement the raw TCP protocol. This ensures the test harness accurately mimics real production 
client behaviour and validates the bridge layer alongside the server.

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

## 2. OAuth Token Acquisition

### Overview
Before connecting to the lobby WebSocket, the client must obtain a **JWT access token** via OAuth2. FAF uses [Ory Hydra](https://www.ory.sh/hydra/) as its OAuth2/OIDC provider, with the [faf-user-service](https://github.com/FAForever/faf-user-service) acting as the login backend.

### Production Endpoints

| Endpoint | URL |
|---|---|
| OAuth2 Base (Hydra) | `https://hydra.faforever.com` |
| Authorization | `https://hydra.faforever.com/oauth2/auth` |
| Token | `https://hydra.faforever.com/oauth2/token` |

### Local Development Endpoints

| Endpoint | URL |
|---|---|
| OAuth2 Base (Hydra) | `http://localhost:4444` |
| Authorization | `http://localhost:4444/oauth2/auth` |
| Token | `http://localhost:4444/oauth2/token` |

Local setup uses `docker compose up -d` from the [faf-user-service](https://github.com/FAForever/faf-user-service) repo, which automatically creates an OAuth client with client ID `faf-client` and redirect URL `http://127.0.0.1`.

### Client Configuration (Production)

From the [downlords-faf-client](https://github.com/FAForever/downlords-faf-client) production config:

| Parameter | Value |
|---|---|
| Client ID | `2e8808cf-5889-469b-b2c3-01f0cc58c4af` |
| Scopes | `openid offline public_profile upload_map upload_mod lobby` |
| Redirect URI | `http://127.0.0.1` (localhost callback) |

> **Note:** As of the Hydra 2.x migration, client IDs are UUIDs by default rather than 
> human-readable strings like `faf-client`.

### Authorization Code Flow

The production FAF client uses the standard **OAuth2 Authorization Code** grant:

```
1. Client opens browser/webview to Hydra authorization endpoint:
   GET https://hydra.faforever.com/oauth2/auth
     ?client_id=2e8808cf-5889-469b-b2c3-01f0cc58c4af
     &response_type=code
     &redirect_uri=http://127.0.0.1
     &scope=openid offline public_profile lobby
     &state=<random-string>

2. User authenticates via faf-user-service login page

3. Hydra redirects browser to:
   http://127.0.0.1?code=<authorization_code>&state=<random-string>

4. Client exchanges the authorization code for tokens:
   POST https://hydra.faforever.com/oauth2/token
   Content-Type: application/x-www-form-urlencoded

   grant_type=authorization_code
   &code=<authorization_code>
   &client_id=2e8808cf-5889-469b-b2c3-01f0cc58c4af
   &redirect_uri=http://127.0.0.1

5. Hydra responds with:
   {
     "access_token": "eyJhbGciOiJSUzI1NiJ9...",
     "token_type": "bearer",
     "expires_in": 43200,
     "refresh_token": "...",
     "id_token": "...",
     "scope": "openid offline public_profile lobby"
   }

6. The access_token (JWT) is used as the `token` field in the
   WebSocket `auth` message (see Section 3).
```

### Mock Client Considerations

The Authorization Code flow requires a browser interaction, which is unsuitable for a headless 
CLI mock. Possible approaches for the mock client:

1. **Local dev with test credentials**: Run the faf-user-service Docker stack locally, which 
   pre-seeds test users. Automate the browser flow programmatically (HTTP requests to the 
   authorization and login endpoints, following redirects) to obtain a token without manual 
   interaction. (Avoid if possible)

2. **Pre-obtained token**: Obtain a token once manually and pass it to the mock client via 
   environment variable or config file. Tokens expire (default ~12 hours / 43200 seconds), 
   so this is only viable for short test sessions. (May be the best way to unblock developement initally)

3. **Ask FAF developers**: The FAF team may be able to provide a test client configured with 
   the `client_credentials` grant type, which allows direct token acquisition without browser 
   interaction. The faf-stack configuration shows M2M client credentials are used for other 
   services (e.g., `faf-website-public`). Contact the team at 
   [FAF Zulip](https://faforever.zulipchat.com/). (Preferred, allows CI pipeline to run indefinitely)

### Scope Relevance

The `lobby` scope is the critical one for WebSocket access. The mock client's minimum required 
scopes are:

| Scope | Purpose |
|---|---|
| `lobby` | Required for WebSocket lobby server access |
| `openid` | Standard OIDC, provides identity claims in the JWT |

Other scopes (`upload_map`, `upload_mod`, `public_profile`, `offline`) are not required for 
the mock client's core functionality.

### Key Takeaway
The mock client ultimately needs a valid OAuth2 **access token** (JWT bearer token) issued by FAF's Hydra instance. This token is obtained before the WebSocket login sequence and is sent in the `token` field of the lobby `auth` message.

### Sources
- [downlords-faf-client production config](https://github.com/FAForever/downlords-faf-client/blob/develop/src/main/resources/application-prod.yml)
- [faf-user-service](https://github.com/FAForever/faf-user-service) — local OAuth setup
- [Hydra OAuth PR #2175](https://github.com/FAForever/downlords-faf-client/pull/2175) — original Hydra integration
- [Hydra 2.x migration PR #3403](https://github.com/FAForever/downlords-faf-client/pull/3403) — UUID client IDs
- [faf-stack .env](https://github.com/FAForever/website/blob/develop/.env.faf-stack) — M2M client config reference

