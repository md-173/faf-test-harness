<a id="section-1-scope"></a>
## 1. Scope

This document specifies the **byte-level wire format of the GPGNet protocol** used on the local TCP socket between the game process (`ForgedAlliance.exe` / `mock-game`) and the ICE adapter (`faf-ice-adapter` / `faf-pioneer`). It is the reference document the Mock Game implements against in [3.2.2 GPGNet TCP Interface] and the spec the PoC parser in [2.2.9] validates.

The JSON-wrapped form of GPGNet (`{"target": "game", "command": ..., "args": [...]}`) that transits the lobby WebSocket is documented in [§6 of lobby-protocol-spec.md](lobby-protocol-spec.md#section-6-gpgnet-wrapping) and is **out of scope** here. This spec covers only the local binary form.

### Source-of-truth hierarchy

| Source | Role |
|---|---|
| [java-ice-adapter](https://github.com/FAForever/java-ice-adapter) (`FaDataInputStream`, `FaDataOutputStream`, `GPGNetServer`) | Reference implementation the Mock Game is paired against in production; defines what bytes our parser must accept. |
| [faf-pioneer](https://github.com/FAForever/faf-pioneer) (`faf/stream_reader.go`, `faf/stream_writer.go`, `gpgnet/`) | Independent Go re-implementation; cross-checks the Java codec and is authoritative for typed argument signatures via its `cmd_*.go` registry. |
| [faf-pioneer `docs/gpgnet.md`](https://github.com/FAForever/faf-pioneer/blob/master/docs/gpgnet.md) | The only existing prose-form GPGNet spec. Provides the BNF grammar reproduced in §2 below. |
| [faf-api-specs `lobby-to-client/game-state-machine.md`](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) | Authoritative for command catalog and lifecycle semantics. |
| [FAForever/fa](https://github.com/FAForever/fa) (`lua/ui/lobby/autolobby.lua`, `lua/ui/lobby/autolobby/components/AutolobbyServerCommunicationsComponent.lua`, `lua/ui/lobby/lobby.lua`, `lua/ui/game/gamemain.lua`) | **Game-side ground truth** for command names and Lua-level arg shapes. The byte layer is below the Lua layer (Moho engine binding, closed-source) and is *not* visible here, so the FA repo confirms catalog and arg counts but not wire encoding. |
| [anykey111/fa-mp-test](https://github.com/anykey111/fa-mp-test) | Game-side C struct offsets; consulted only for ground-truth tie-breaking. |
| [FAForever/server](https://github.com/FAForever/server) (`server/gameconnection.py`) | Lobby server's GPGNet-over-WebSocket handlers — authoritative for arg *types* the server expects when commands are forwarded over the wrapper. Treated as secondary for the local TCP wire but useful where FA's Lua doesn't constrain a type. |

A third reference codec — [FAForever/kotlin-ice-adapter](https://github.com/FAForever/kotlin-ice-adapter) — also implements GPGNet. Cross-referenced where it disagrees with the Java/Go codecs; otherwise omitted to avoid clutter.

When the Java codec and the Go codec disagree on the wire (see [§4.4](#section-4-4-known-issues)), this document treats the **Java codec as authoritative for what the Mock Game must produce and accept**, because the Mock Game pairs with the Java adapter in the test harness. When the codecs and the FA Lua disagree on an *argument signature* (e.g. arg count for `JoinGame`, see [§7.3](#section-7-3-arg-discrepancy)), FA's Lua entry-point definition is authoritative for what the engine accepts.

---

<a id="section-2-frame-grammar"></a>
## 2. Frame Grammar

The Go codec's `docs/gpgnet.md` provides the only published Backus-Naur-Form. Reproduced verbatim:

```
<message>              ::= <command> <size> <chunks>
<command>              ::= <string>
<size>                 ::= uint32
<string>               ::= <size> []byte
<chunks>               ::= <chunk> | <chunk> <chunks>
<chunk>                ::= <type_int> uint32 | <type_string> <string> | <type_followup_string> <string>
<type_int>             ::= byte = 0x00
<type_string>          ::= byte = 0x01
<type_followup_string> ::= byte = 0x02
```

The grammar above is **internally inconsistent with both reference implementations** in one way. The implementation behaviour is treated as the source of truth (see [§4.4](#section-4-4-known-issues)):

- **Signedness.** The BNF declares `uint32` for the `size` and `int chunk` fields. Both Java (`LittleEndianDataInputStream.readInt()`, returning `int`) and Go (`binary.Read(..., &x int32, ...)`) actually treat these fields as **signed `int32`**. Java's `MAX_CHUNK_SIZE = 10` guard makes practical signedness moot, but the value is signed on the wire.

### 2.1 Simplified description

A frame is the concatenation of:

1. A **command-name string** (length-prefixed, see [§3](#section-3-strings)).
2. A **chunk-count field** — a 4-byte little-endian signed integer giving the number of arguments that follow.
3. **N chunks**, each consisting of a 1-byte type tag followed by a tag-specific payload (see [§4](#section-4-chunks)).

There is **no outer frame envelope**. There is no preceding "frame length" field. The boundary between two adjacent frames in a TCP stream is purely structural: a parser knows the frame is complete when it has consumed exactly `command` + `chunkCount` + `chunkCount` chunks.

### Sources

- [`docs/gpgnet.md` — BNF block](https://github.com/FAForever/faf-pioneer/blob/master/docs/gpgnet.md)
- [`FaDataInputStream.java#L23-L32`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L23-L32) — `readChunks()`: chunk-count read followed by per-chunk tag + payload loop, with no outer length field consumed before this method is called.
- [`stream_reader.go#L20-L56`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L20-L56) — `ReadChunks()`: identical structure in Go.

---

<a id="section-3-strings"></a>
## 3. String Encoding

### 3.1 Wire layout

| Field | Width | Type | Purpose |
|---|---|---|---|
| `length` | 4 bytes | int32 little-endian | **Byte length** of the UTF-8 payload (see [§4.4](#section-4-4-known-issues) for the Java/Go discrepancy) |
| `payload` | `length` bytes | UTF-8 | The string content |

There is no NUL terminator. There is no BOM. Strings are not zero-padded.

### 3.2 Character set

Both codecs treat strings as **UTF-8** on the wire:

- Java: `private final Charset charset = StandardCharsets.UTF_8;` — [`FaDataInputStream.java#L17`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L17), [`FaDataOutputStream.java#L22`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L22). Decode: `new String(buffer, charset)` ([line 59](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L59)).
- Go: writes raw bytes via `w.w.Write([]byte(s))` ([`stream_writer.go#L27`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L27)) and decodes via `string(buf)` ([`stream_reader.go#L76`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L76)). Go's native string type is UTF-8.

In practice almost all GPGNet payloads (command names, map names, player logins, JSON blobs) are ASCII, so the encoding rarely matters. The notable exception is `Chat`, which can contain arbitrary UTF-8.

### 3.3 Reader-side substitution: `/t` → `\t`, `/n` → `\n`

Both readers post-process incoming strings, replacing the literal two-character sequences `/t` and `/n` with single tab and newline characters respectively:

- Java: [`FaDataInputStream.java#L41`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L41) — `chunks.add(readString().replace("/t", "\t").replace("/n", "\n"));`
- Go: [`stream_reader.go#L54`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L54) — `s = replaceSpecial(s)`

Neither writer performs the inverse substitution before sending, so this only matters for strings originating from `[FA.exe]` (Lua-emitted) and is effectively a no-op in the Mock Game direction. The Mock Game **must not** apply the inverse substitution when sending; the adapter applies the forward substitution on receipt.

### Sources

- [`FaDataInputStream.java#L17`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L17), [`#L41`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L41), [`#L54-L60`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L54-L60)
- [`FaDataOutputStream.java#L57-L60`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L57-L60)
- [`stream_reader.go#L54`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L54), [`#L58-L76`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L58-L76)
- [`stream_writer.go#L21-L28`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L21-L28)

---

<a id="section-4-chunks"></a>
## 4. Chunks (Typed Arguments)

A frame's chunk section is preceded by the chunk count and contains exactly that many chunks. Each chunk is `[1-byte tag][tag-specific payload]`.

### 4.1 Type-tag table

| Tag (hex) | Java constant | Go constant | Wire layout following the tag | Decoded type |
|---|---|---|---|---|
| `0x00` | `FIELD_TYPE_INT` ([line 14](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L14)) | `IntType` ([writer L98](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L98)) | 4 bytes int32 little-endian | int |
| `0x01` | `FIELD_TYPE_STRING` ([writer L18](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L18)) | `STRING` ([writer L99](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L99)) | length-prefixed UTF-8 (see [§3](#section-3-strings)) | string |
| `0x02` | `FIELD_TYPE_FOLLOWING_STRING` ([writer L19](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L19)) | `FOLLOWUP_STRING` ([writer L100](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L100)) | length-prefixed UTF-8 (identical to `0x01`) | string |

`0x02` is defined as a constant by both codecs but is **never emitted by either writer** (the Go writer's type switch only handles ints and strings; the Java writer's `writeArgs` likewise only emits `0x00` or `0x01`). It is included in the table for completeness because both readers accept it.

### 4.2 Reader fallback for unknown tags

Both readers treat **any tag value not equal to `0x00`** as a string. The switch's `default` branch consumes the bytes via the same length-prefixed-UTF-8 path as `0x01`:

- Java: [`FaDataInputStream.java#L34-L42`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L34-L42) — only `case FIELD_TYPE_INT`, then `default` reading a string.
- Go: [`stream_reader.go#L40-L54`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L40-L54) — only `case IntType`, then `default` reading a string.

A consequence: **mod-protocol extensions cannot introduce new typed tags** without breaking both readers. A new tag would silently be parsed as a length-prefixed string, almost certainly with a length value that violates `MaxStringLength` and crashes the reader.

### 4.3 Bounds enforcement

Both codecs enforce hard caps:

| Limit | Value (Java) | Value (Go) | Source |
|---|---|---|---|
| `MAX_CHUNK_SIZE` | 10 | (`MaxChunkSize` — same intent, see Go reader) | [`FaDataInputStream.java#L13`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L13), [`stream_reader.go#L29`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L29) |
| `MaxStringLength` | (implicit; `byte[size]` will throw `OutOfMemoryError` on absurd sizes) | explicit constant | [`stream_reader.go#L66`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L66) |

Frames with `chunkCount > 10` raise `IOException` in the Java codec. The Mock Game must not emit any frame with more than 10 args; in practice the largest in-spec frame (`CreateLobby`) has 5.

<a id="section-4-4-known-issues"></a>
### 4.4 Known issues / interop discrepancies

These are real defects in the upstream codebase. Document them so the Mock Game does not silently inherit the bug:

1. **Java string-length prefix uses `string.length()`, not byte count.** The Java writer computes the prefix as `string.length()` ([`FaDataOutputStream.java#L58`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L57-L60)) — a UTF-16 code-unit count — but writes the body as `string.getBytes(charset)` (UTF-8 bytes). For ASCII-only strings the two values are equal and the bug is invisible. For any non-ASCII string the prefix understates the byte length, the Java reader truncates the body to the under-reported length, and the next frame begins mid-byte-stream — the connection desyncs and is unrecoverable. The Go writer correctly uses `int32(len(s))` (UTF-8 byte count, [`stream_writer.go#L23`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L23)).

   **Mock Game policy:** Compute the prefix as the UTF-8 byte length (i.e. mirror the Go writer, not the Java writer). All Mock Game payloads are ASCII in normal operation, so no real-world Mock Game frame will currently expose the bug — but the parser MUST decode using the prefix verbatim, and any test that round-trips non-ASCII payloads should be marked as exercising the upstream defect.

2. **`int32` vs `uint32`.** The BNF declares `uint32`. Both implementations use signed `int32`. Negative lengths are theoretically representable on the wire and would be rejected by the size-bound checks. Document the field type as **signed `int32` little-endian**.

### Sources

- [`FaDataInputStream.java#L13-L14`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L13-L14), [`#L34-L42`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L34-L42)
- [`FaDataOutputStream.java#L17-L19`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L17-L19), [`#L57-L60`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java#L57-L60)
- [`stream_reader.go#L29`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L29), [`#L40-L54`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L40-L54), [`#L66`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L66)
- [`stream_writer.go#L23`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L23), [`#L98-L100`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go#L98-L100)

---

<a id="section-5-stream-handling"></a>
## 5. Frame Boundaries and Partial-Frame Handling

### 5.1 Boundary detection

Because there is no outer frame-length prefix ([§2.1](#section-2-frame-grammar)), a parser cannot skip a malformed message — it must parse to know where the next frame starts. The boundary is implicit and recovered by consuming exactly:

```
[ command_name (4-byte length + N bytes) ]
[ chunk_count (4 bytes int32 LE) ]
[ chunk_count × ( 1-byte tag + tag payload ) ]
```

A frame is complete when the parser has consumed the last chunk's payload. The next byte is the first byte of the next frame.

### 5.2 Partial-frame behaviour

Both reference codecs use **blocking reads**:

- Java wraps the socket `InputStream` in `BufferedInputStream` → `LittleEndianDataInputStream` and reads with `readInt()` and `readFully(byte[])`. These block until the requested bytes arrive or throw `EOFException` if the stream closes mid-frame ([`FaDataInputStream.java#L19-L20`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L19-L20), [`#L58`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L58)).
- Go uses `binary.Read` (which calls `io.ReadFull` internally) and `io.ReadFull` directly ([`stream_reader.go#L72`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L72)). Same semantics: block until all bytes arrive, return `io.ErrUnexpectedEOF` on close mid-frame.

Neither codec returns `null`/empty on partial frames. Neither codec maintains a state machine across `read()` calls. **A non-blocking parser (e.g. one driven by Netty's `ByteToMessageDecoder`) must implement its own buffered, length-aware state machine.** The Mock Game's parser implementation in [3.2.2] should be blocking-read on its dedicated socket thread, mirroring the upstream model — there is no upstream pattern for non-blocking GPGNet.

### 5.3 Error recovery

There is none. A truncated or malformed frame produces an unrecoverable parse error in both codecs (`IOException` / Go error return). The expected response is to close the socket. The Mock Game inherits this contract: once the parser desyncs, the only correct action is to terminate the GPGNet connection.

### Sources

- [`FaDataInputStream.java#L19-L60`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java#L19-L60)
- [`stream_reader.go#L20-L80`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go#L20-L80)

---

<a id="section-6-byte-map"></a>
## 6. Worked Byte Map

A complete `GameState` frame with arg `"Lobby"` is 27 bytes:

```
offset  hex                                              meaning
------  -----------------------------------------------  --------------------------------
0x00    09 00 00 00                                      command-name length = 9 (int32 LE)
0x04    47 61 6d 65 53 74 61 74 65                       "GameState" (UTF-8, 9 bytes)
0x0D    01 00 00 00                                      chunk count = 1 (int32 LE)
0x11    01                                               chunk[0] tag = 0x01 (string)
0x12    05 00 00 00                                      chunk[0] string length = 5
0x16    4c 6f 62 62 79                                   "Lobby" (UTF-8, 5 bytes)
0x1B    -- end of frame; next byte is start of next frame --
```

A `CreateLobby(0, 6112, "TestPlayer", 1234, 1)` frame is 54 bytes:

```
offset  hex                                              meaning
------  -----------------------------------------------  --------------------------------
0x00    0B 00 00 00                                      command length = 11
0x04    43 72 65 61 74 65 4c 6f 62 62 79                 "CreateLobby"
0x0F    05 00 00 00                                      chunk count = 5
0x13    00  00 00 00 00                                  chunk[0]: int 0
0x18    00  E0 17 00 00                                  chunk[1]: int 6112
0x1D    01  0A 00 00 00 54 65 73 74 50 6c 61 79 65 72    chunk[2]: string "TestPlayer"
0x2C    00  D2 04 00 00                                  chunk[3]: int 1234
0x31    00  01 00 00 00                                  chunk[4]: int 1
0x36    -- end of frame --
```

Multi-byte ints are little-endian throughout (`6112 = 0x000017E0`, on the wire as `E0 17 00 00`).

---

<a id="section-7-command-catalog"></a>
## 7. Command Catalog

This catalog lists every GPGNet command observed crossing the local TCP socket. The argument signatures are sourced from `faf-pioneer`'s typed `gpgnet/cmd_*.go` registry where one exists, otherwise from the [faf-api-specs game-state-machine table](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md).

**Direction codes:** `G→A` = game emits, adapter receives. `A→G` = adapter emits, game receives.

**Class:** `Lifecycle` = required for the Idle→Lobby→Launching→Ended path. `Lobby` = lobby configuration. `LIVE` = sent during gameplay. `Fault` = error/health signalling. `ICE` = ICE negotiation passthrough.

### 7.1 Game → Adapter (Mock Game emits)

| Command | Args (ordered) | Class | Source |
|---|---|---|---|
| `GameState` | `state:string` ∈ `{"Idle","Lobby","Launching","Ended"}` | **Lifecycle** | [`cmd_game_state.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_game_state.go) |
| `GameOption` | `key:string, value:any (specific keys have specific type constraints)` | Lobby | [`cmd_game_option.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_game_option.go) |
| `PlayerOption` | `player_id:int, key:string, value:any (specific keys have specific type constraints)` | Lobby | [game-state-machine.md](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) (no typed Go struct) |
| `AIOption` | `ai_name:string, key:string, value:string` | Lobby | game-state-machine.md |
| `ClearSlot` | `slot:int` | Lobby | game-state-machine.md |
| `GameMods` | `mode:string, args:...` (variadic; `"activated"` + count, or `"uids"` + space-separated string) | Lobby | game-state-machine.md |
| `EnforceRating` | _(none)_ | Lobby | game-state-machine.md |
| `GameFull` | _(none)_ | Lobby | [`cmd_game_full.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_game_full.go) |
| `Chat` | `message:string` | Lobby | [`cmd_chat.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_chat.go) |
| `LaunchStatus` | `status:string` | Lobby | game-state-machine.md |
| `GameResult` | `army:int, result_string:string` (e.g. `"victory 10"`) | LIVE | [`cmd_game_result.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_game_result.go) |
| `GameEnded` | _(none)_ | **Lifecycle** | [`cmd_game_ended.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_game_ended.go) |
| `JsonStats` | `stats_json:string` | LIVE | [`cmd_json_stats.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_json_stats.go) |
| `OperationComplete` | `primary:int, secondary:int, time_delta:int` | LIVE | game-state-machine.md |
| `Desync` | _(none)_ | Fault | game-state-machine.md |
| `TeamkillHappened` | `gametime:int, victim_id:int, victim_name:string, tk_id:int, tk_name:string` | LIVE | game-state-machine.md |
| `TeamkillReport` | `gametime:int, reporter_id:int, reporter_name:string, tk_id:int, tk_name:string` | LIVE | game-state-machine.md |
| `IceMsg` | `receiver_id:int, ice_msg_json:string` | ICE | game-state-machine.md |
| `Bottleneck` | `code:int, ...args` (see [§7.4](#section-7-4-bottleneck)) | **Fault** | game-state-machine.md |
| `BottleneckCleared` | _(none)_ | **Fault** | game-state-machine.md |
| `Disconnected` | `uid:string` (FA Lua sends `string.format("%d", uid)` — wire chunk is type `0x01`, NOT `0x00`) | LIVE | [`lobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/lobby.lua) |
| `Rehost` | `...args` (marked unused by spec) | Lobby | game-state-machine.md |
| `EstablishedPeer` | `peer_id:int` | LIVE | [`AutolobbyServerCommunicationsComponent.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby/components/AutolobbyServerCommunicationsComponent.lua) — emitted by FA, no server handler; consumed by ICE adapter only |
| `DisconnectedPeer` | `peer_id:int` | LIVE | same file — adapter-only |
| `BEAT` | `game_tick:int, game_speed:int` | LIVE | [`gamemain.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/game/gamemain.lua) — heartbeat; not handled by lobby server |

#### 7.1.1 GameOption keys

The following is a list of `GameOption` keys that have obtained from official FAF sources.
This list is not exhaustive, and each source lists a different amount and set of keys and associated values.
Additionally, what each key does is not apparent from the code in all cases.

For boolean options, the server considers any value of `True`, `"true"`, `"on"`, `"yes"`, and `1` to be equivalent.
Similarly, any value of `False`, `"false"`, `"off"`, `"no"`, and `0` are equivalent.
These are case insensitive.

| Key                  | Possible values | (Inferred) usage/notes | Source |
|----------------------|-----------------|------------------------|--------|
| AIReplacement        | True, False | Whether a game can replace a disconnected player with an AI | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| AllowObservers       | True, False | Whether observers are allowed in the game | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| AutoTeams            | none, manual, tvsb, lvsr, pvsi, unknown | Automatic configuration of teams. 'tvsb' means 'top vs bottom', 'lvsr' means 'left vs right', 'pvsi' means 'even vs uneven' | [faf-pioneer](https://github.com/FAForever/faf-pioneer/blob/main/gpgnet/cmd_game_option.go), [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| CheatsEnabled        | True, False | Whether cheats can be used in the game | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java), [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| FogOfWar             | explored, ??? | Whether the map is fully releaved or must be explored, whether explored but unobserver regions update | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| GameSpeed            | normal, ??? | The tick speed of the game | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| NoRushOption         | True, False | ??? | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| PrebuiltUnits        | True, False | Whether armies can start with prebuilt units | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java), [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| RestrictedCategories | integer | ??? | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| RevealCivilians      | True, False | Whether civilians on the map are revealed or must be found | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| ScenarioFile         | string (filepath) | A lua file associated with a map | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| Score                | True, False | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| Share                | FullShare, ShareUntilDeath, PartialShare, TransferToKiller, Defectors, CivilianDeserter, unknown | ??? | [faf-pioneer](https://github.com/FAForever/faf-pioneer/blob/main/gpgnet/cmd_game_option.go), [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| Slots                | integer | A maximum number of players that can be in the game | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| TeamLock             | locked, unlocked, unknown | ??? | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java), [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| TeamSpawn            | fixed, random, balanced, balanced_flex, random_reveal, balanced_reveal, balanced_reveal_mirrored, balanced_flex_reveal, unknown | Where teams spawn on the map | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| Title                | string | Title of the game | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py) |
| UnitCap              | integer | A maximum number of units that can be in an army | [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| Unranked             | Yes, No | Whether the game counts for ranking | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py), [faf-pioneer](https://github.com/FAForever/faf-pioneer/blob/main/gpgnet/cmd_game_option.go), [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |
| Victory              | demoralization, domination, eradication, sandbox, unknown | Victory condition | [server](https://github.com/FAForever/server/blob/develop/server/games/game.py), [faf-java-commons](https://github.com/FAForever/faf-java-commons/blob/develop/data/src/main/java/com/faforever/commons/replay/header/GameOptions.java) |

#### 7.1.2 PlayerOption keys

The following is a list of `PlayerOption` keys obtained from [server](https://github.com/FAForever/server/blob/develop/tests/integration_tests/test_game.py)
This list may not be exhaustive.

| Key       | Possible values | Usage                                                       |
|-----------|-----------------|-------------------------------------------------------------|
| Army      | integer         | The individual army ID of the player                        |
| Team      | integer         | The team to which the player belongs                        |
| StartSpot | integer         | The ID of the spot on the map that the player will start in |
| Faction   | integer         | A number presenting which faction the player is using (e.g. United Earth Federation = 1, Aeon Illuminate = 2, Cybran Nation = 3, Seraphim = 4) |
| Color     | integer         | A color assigned to the player                              |

### 7.2 Adapter → Game (Mock Game receives) <a id="section-7-2-adapter-to-game"></a>

| Command | Args (ordered) | Class | Source |
|---|---|---|---|
| `CreateLobby` | `init_mode:int, port:int, login:string, player_id:int, nat_traversal_provider:int (= 1)` — FA's Lua names the 5th arg `natTraversalProvider`; both adapters hardcode it to `1`. | **Lifecycle** | [`cmd_create_loby.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_create_loby.go), [`GPGNetServer.java#L111-L117`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L111-L117), [`autolobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby.lua) `function CreateLobby` |
| `HostGame` | `map_name:string` (FA Lua's `function HostGame(gameName, scenarioFileName, singlePlayer)` accepts up to 3 args; both adapters send only 1, so the trailing two are `nil` in Lua) | Lifecycle | [`cmd_host_game.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_host_game.go), [`autolobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby.lua) `function HostGame` |
| `JoinGame` | `net_address:string, remote_player_login:string, remote_player_id:int` (3 args on the wire — see [§7.3](#section-7-3-arg-discrepancy) for the FA Lua arity discrepancy) | Lifecycle | [`cmd_join_game.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_join_game.go), [`autolobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby.lua) `function JoinGame` |
| `ConnectToPeer` | `net_address:string, remote_player_login:string, remote_player_id:int` | Lifecycle | [`cmd_connect_to_peer.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_connect_to_peer.go) (see [§7.3](#section-7-3-arg-discrepancy)) |
| `DisconnectFromPeer` | `remote_player_id:int` | Lifecycle | [`cmd_disconnect_from_peer.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_disconnect_from_peer.go) |
| `IceMsg` | `sender_id:int, ice_msg_json:string` | ICE | game-state-machine.md |

`CreateLobby` is the only adapter→game frame the Java adapter sends from inside `GPGNetServer.java` directly (in response to `GameState("Idle")`, [`GPGNetServer.java#L106-L117`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L106-L117)). The other adapter→game frames originate elsewhere in the adapter (RPC handlers) and are dispatched via [`GPGNetServer.sendGpgnetMessage` (line 137)](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L137) → [`gpgnetOut.writeMessage` (line 140)](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L140).

### 7.3 `JoinGame` / `ConnectToPeer` arg-count discrepancy <a id="section-7-3-arg-discrepancy"></a>

Three references disagree on argument count and types:

| Source | `JoinGame` | `ConnectToPeer` |
|---|---|---|
| [faf-pioneer `gpgnet/`](https://github.com/FAForever/faf-pioneer/tree/master/gpgnet) typed structs (adapter→game wire) | **3 args**: `net_address:string, remote_player_login:string, remote_player_id:int` | **3 args**: `net_address:string, remote_player_login:string, remote_player_id:int` |
| [`autolobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby.lua) (FA's Lua entry-points) | **4 args**: `function JoinGame(address, asObserver, playerName, uid)` | **3 args**: `function ConnectToPeer(addressAndPort, name, uid)` |
| [faf-api-specs game-state-machine.md](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) (server → adapter, JSON-wrapped) | 2 args: `player_name, player_uid` | 3 args: `player_name, player_uid, offer:bool` |

**Resolution.**

- **`ConnectToPeer`** — all three layers agree on 3 args. The api-specs `offer:bool` is consumed by the adapter (used to choose ICE initiator role) and **not passed through** to FA; the adapter resolves and substitutes `net_address` into arg[0] before forwarding.
- **`JoinGame`** — FA's Lua entry expects **4 args** including `asObserver:bool`, but both reference adapters (Java + Go) emit only **3** on the wire. The result is that `asObserver` arrives in Lua as `nil`. This is a real adapter↔game arity mismatch in upstream code; FA's Lua tolerates it because `nil` evaluates as `false` for the observer check. **Mock Game policy:** match the upstream adapter behaviour — emit/accept 3 args matching pioneer's typed struct. Document this as an inherited quirk.

This spec is authoritative for the **local TCP wire** form: **3 args, types as in the faf-pioneer Go structs**, for both commands.

### 7.4 Bottleneck / BottleneckCleared <a id="section-7-4-bottleneck"></a>

Both commands flow exclusively **Game → Adapter**. Neither codec switches on them: the Java adapter's `processGpgnetMessage` falls through to the `default` arm at [`GPGNetServer.java#L129-L133`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L129-L133) and forwards opaquely via `rpcService.onGpgNetMessageReceived(command, args)`; the Go adapter likewise has no typed struct in `gpgnet/`. They are pass-through to the lobby server.

| Command | Direction | Args | Phase | Behaviour |
|---|---|---|---|---|
| `Bottleneck` | G→A→server | `code:string, ...args:string` (server treats as variadic strings — see below) | LIVE | FA reports a simulation-pipeline stall. |
| `BottleneckCleared` | G→A→server | _(none)_ | LIVE | FA reports the stall has resolved. |

**`Bottleneck` arg types come from the lobby server, not FA's Lua.** Neither FA's Lua paths nor the codecs typed-emit `Bottleneck`; the closed-source engine emits it directly. The lobby server's [`handle_bottleneck`](https://github.com/FAForever/server/blob/develop/server/gameconnection.py) handler signature is `code:str, *args:str`. A real-world payload logged by the server is `["data", "19508", "517268,516974,344419", "5980.1"]` — i.e. the engine sends `code` as a string (`"data"`), not an int as the faf-api-specs table loosely states. The trailing args are stringly-typed and the server logs them without parsing.

**Mock Game policy:** emit `Bottleneck` with `code:string` (chunk tag `0x01`). Trailing args are optional and may be omitted. Parser policy: surface the trailing chunks as raw chunk records (preserving tag + payload) without claiming to type them.

### Sources

- [`faf-pioneer/gpgnet/`](https://github.com/FAForever/faf-pioneer/tree/master/gpgnet) — typed command registry (12 cmd_*.go files)
- [`faf-pioneer/docs/gpgnet.md`](https://github.com/FAForever/faf-pioneer/blob/master/docs/gpgnet.md) — adapter→game arg tables
- [`faf-api-specs/lobby-to-client/game-state-machine.md`](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) — full FA→Server and Server→FA tables, lifecycle phases
- [`GPGNetServer.java#L104-L140`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L104-L140) — adapter dispatch behaviour for typed vs opaque commands

---

<a id="section-8-lifecycle-walkthrough"></a>
## 8. Lifecycle Walkthrough (Idle → Lobby → Launching → Ended)

A reference frame ordering for a custom-game host. Frame contents are summarised; see [§7](#section-7-command-catalog) for full arg signatures.

```
Time   Direction   Frame
 t0    G → A       GameState("Idle")
 t1    A → G       CreateLobby(init_mode=0, port, login, player_id, 1)
 t2    G → A       GameState("Lobby")
                   (host configures lobby — multiple GameOption / PlayerOption / GameMods)
 ...   G → A       GameOption("Victory", "demoralization")
 ...   G → A       PlayerOption(player_id, "Faction", "1")
 t3    A → G       HostGame(map_folder_name)              // triggered by lobby server
       (other peers join; for each peer:)
 ...   A → G       ConnectToPeer(net_addr, login, peer_id)
 t4    G → A       GameState("Launching")
                   (gameplay; per-tick traffic is over the IA↔IA UDP path, not GPGNet)
 ...   G → A       Bottleneck(code) / BottleneckCleared    // optional, on stall
 t5    G → A       GameResult(army=1, "victory 10")        // one per army
 ...   G → A       JsonStats(stats_json)
 t6    G → A       GameEnded
 t7    G → A       GameState("Ended")                      // final state
```

For a custom-game **joiner**, replace `HostGame` at `t3` with `JoinGame(net_addr, host_login, host_id)`, plus zero or more `ConnectToPeer` frames for additional peers.

For a **matchmaker** game, the `init_mode` arg of `CreateLobby` at `t1` is `1` (Auto) instead of `0` (Normal); the lobby phase skips manual `GameOption`/`PlayerOption` configuration because the server pre-sets the slot.

The four `GameState` strings are exact: `"Idle"`, `"Lobby"`, `"Launching"`, `"Ended"`. There is no `"Hosted"` or `"Live"` value.

**Engine vs Lua emit-site.** Of these four values, only `"Launching"` is emitted from FA's open-source Lua layer ([`AutolobbyServerCommunicationsComponent.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby/components/AutolobbyServerCommunicationsComponent.lua) — there is an explicit guard `if value ~= 'Launching' then return end`). `"Idle"`, `"Lobby"`, and `"Ended"` originate from the closed-source Moho engine itself, before Lua sees them. The lobby server's [`handle_game_state`](https://github.com/FAForever/server/blob/develop/server/gameconnection.py) accepts all four. The Lua type alias `UILobbyState` also enumerates an internal `"None"` value, but it is never sent on the wire. **Mock Game policy:** emit all four values as required by the lifecycle in §8 — none of the engine-internal split affects the Mock Game, which simulates the engine's behaviour.

### 8.1 Preconditions before `t0` <a id="section-8-1-preconditions"></a>

The walkthrough starts at `GameState("Idle")`, but the Java adapter constrains *when* that first frame may be sent. Breaking either condition below kills the adapter's GPGNet listener thread on the first frame, both times with the same signature: the adapter logs `IllegalStateException: gameState must not change to null` and the game side sees the socket close. `CreateLobby` is still sent before the crash, so the handshake half-completes and then dies, which looks like a codec fault but is not one.

Both conditions trace to a single field. A connected client is reachable statically only through `GPGNetServer.currentClient` ([`GPGNetServer.java#L282`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L282)), and the accept loop assigns it only *after* the `GPGNetClient` constructor returns ([`#L254`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L254)). The constructor has already started the listener thread by then ([`#L104`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L104)), so any frame processed before the assignment sees `currentClient == null`.

1. **A JSON-RPC peer must be connected to the adapter.** Between starting the listener and returning, the constructor calls `rpcService.onConnectionStateChanged("Connected")` ([`#L106`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L106)), which reaches `RPCService.getPeerOrWait()` ([`RPCService.java#L107-L114`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/rpc/RPCService.java#L107-L114)). That method calls `tcpServer.getFirstPeer().get()` and blocks without bound until the first JSON-RPC client connects. With no peer the constructor never returns and `currentClient` is never assigned at all; the diagnostic sign is a missing `"GPGNetClient has connected"` line ([`#L107`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L107)) in the adapter log. The wait is satisfied by a **plain TCP connection**, not by any JSON-RPC call: JJsonRpc's listener completes `firstPeer` from `addPeer` the moment `accept()` returns ([`SocketListener.java#L36-L39`](https://github.com/FAForever/JJsonRpc/blob/37669e0fed/src/main/java/com/nbarraille/jjsonrpc/SocketListener.java#L36-L39), [`TcpServer.java#L48-L58`](https://github.com/FAForever/JJsonRpc/blob/37669e0fed/src/main/java/com/nbarraille/jjsonrpc/TcpServer.java#L48-L58)).
2. **The first `GameState` must not be sent the instant the socket opens.** Even with a peer attached, the constructor tail can trail the game's connect, leaving the same window open.

Inside the window the listener reads `GameState("Idle")`, sends `CreateLobby` (that path uses `this`, not `currentClient`), then calls `debug().gameStateChanged()` ([`#L131`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L131)). Telemetry resolves `getGameState()`, which maps over the null `currentClient`, so its `orElseThrow` fires. Only `IOException` is caught around the read loop ([`#L193`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L193)), so the exception closes the input stream and kills the thread, and `onGpgnetConnectionLost()` never runs. The adapter is left holding a client whose reader is dead. Disabling telemetry does not rescue condition 1: `processGpgnetMessage` ends with `rpcService.onGpgNetMessageReceived(...)` ([`#L148`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L148)), which calls `getPeerOrWait()` again, so the listener would block there on every frame instead.

The condition 2 crash signature depends on the telemetry debugger still being registered. `TelemetryDebugger` unregisters itself when its websocket connect fails fast ([`TelemetryDebugger.java#L68-L75`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/debug/TelemetryDebugger.java#L68-L75), [`#L117-L126`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/debug/TelemetryDebugger.java#L117-L126)), and without it `gameStateChanged()` dispatches to nobody, so the window closes harmlessly. Condition 1 stands regardless because the block happens in `RPCService` before telemetry is involved. In the recorded runs the debugger stayed registered even with telemetry unreachable, so the documented signature held.

**Neither condition is reachable in production**, which is why upstream has not hit them. `downlords-faf-client` connects its JSON-RPC client to the adapter and only then resolves the future carrying the GPG port the game is launched with ([`IceAdapterImpl.java`](https://github.com/FAForever/downlords-faf-client/blob/develop/src/main/java/com/faforever/client/fa/relay/ice/IceAdapterImpl.java)), so a real game cannot send a byte before the RPC peer exists. The engine also spends seconds booting before emitting `"Idle"`.

**Mock Game policy:** treat the GPGNet connection as usable only once the Mock Client's JSON-RPC connection to the adapter is established. This is an ordering constraint *across* components, which [3.2.4.1] and [3.1.2.7] must honour. The Mock Client already satisfies it because it connects its adapter transport before the game is launched. A standalone game-side test has no client to rely on and must stand in for one by holding a TCP socket open on the adapter's RPC port; `GpgNetConnectionLiveSmokeTest` (3.2.2.4) does that and additionally pauses before its first frame.

Live-verified against `faf-ice-adapter` **3.3.14** (`JJsonRpc 37669e0fed`); the links above are pinned to that tag because the line numbers are load-bearing. The same run confirmed the positive case: the `CreateLobby` frame decoded exactly as [§7.2](#section-7-2-adapter-to-game) specifies, arg for arg, and the adapter sent no other frame during the handshake.

### Sources

- [`game-state-machine.md`](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) — phase definitions and `GameState` value list
- [`GPGNetServer.java#L104-L122`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L104-L122) — Idle → CreateLobby trigger
- [`GPGNetServer.java@3.3.14`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java) / [`RPCService.java@3.3.14`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/rpc/RPCService.java): §8.1 preconditions, the `currentClient` assignment order and the `getPeerOrWait()` block
- [`JJsonRpc@37669e0fed`](https://github.com/FAForever/JJsonRpc/tree/37669e0fed/src/main/java/com/nbarraille/jjsonrpc): `firstPeer` completes on TCP `accept()`, before any JSON-RPC call (the adapter's pinned RPC library)
- [`TelemetryDebugger.java@3.3.14`](https://github.com/FAForever/java-ice-adapter/blob/3.3.14/ice-adapter/src/main/java/com/faforever/iceadapter/debug/TelemetryDebugger.java): self-unregisters on a fast websocket-connect failure, which is what the §8.1 condition 2 signature depends on
- [`IceAdapterImpl.java`](https://github.com/FAForever/downlords-faf-client/blob/develop/src/main/java/com/faforever/client/fa/relay/ice/IceAdapterImpl.java): production ordering, the RPC connect precedes the GPG port the game is launched with

---

<a id="section-9-quick-reference"></a>
## 9. Quick Reference

```
Frame layout (every multi-byte numeric field is int32 little-endian):

  +--------+------------------+--------+---------+ ... +---------+
  | cmdLen | cmdName (UTF-8)  | nChunk | chunk_0 | ... | chunk_n |
  | int32  |   cmdLen bytes   | int32  |         |     |         |
  +--------+------------------+--------+---------+ ... +---------+

Chunk layout (1-byte tag + tag-specific payload):

  Tag 0x00  int:    +-----+--------+
                    | 0x00| int32  |
                    +-----+--------+

  Tag 0x01  string: +-----+--------+----------------+
                    | 0x01|  size  | UTF-8 (size B) |
                    +-----+--------+----------------+

  Tag 0x02  string: same wire layout as 0x01 (rarely emitted)

Hard caps:  chunkCount ≤ 10  (Java: throws on overflow)
Boundary:   structural — no outer envelope, no terminator.
On error:   close the socket. There is no resync.
```

---

## 10. Sources

- Java ICE adapter codec: [`FaDataInputStream.java`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataInputStream.java), [`FaDataOutputStream.java`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/FaDataOutputStream.java), [`GPGNetServer.java`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java)
- faf-pioneer Go codec: [`stream_reader.go`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_reader.go), [`stream_writer.go`](https://github.com/FAForever/faf-pioneer/blob/master/faf/stream_writer.go), [`gpgnet/`](https://github.com/FAForever/faf-pioneer/tree/master/gpgnet), [`docs/gpgnet.md`](https://github.com/FAForever/faf-pioneer/blob/master/docs/gpgnet.md)
- FAF API specs: [`lobby-to-client/game-state-machine.md`](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md)
- Game-side ground truth (Lua emit/receive): [FAForever/fa](https://github.com/FAForever/fa) — [`autolobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby.lua), [`AutolobbyServerCommunicationsComponent.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/autolobby/components/AutolobbyServerCommunicationsComponent.lua), [`lobby.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/lobby/lobby.lua), [`gamemain.lua`](https://github.com/FAForever/fa/blob/develop/lua/ui/game/gamemain.lua)
- Lobby server handlers (arg-type cross-reference): [FAForever/server `gameconnection.py`](https://github.com/FAForever/server/blob/develop/server/gameconnection.py)
- Game-side C struct offsets (consulted): [anykey111/fa-mp-test](https://github.com/anykey111/fa-mp-test)
- Third reference codec (consulted): [FAForever/kotlin-ice-adapter](https://github.com/FAForever/kotlin-ice-adapter)
- Sibling spec: [`lobby-protocol-spec.md`](lobby-protocol-spec.md) — JSON-wrapped GPGNet over the lobby WebSocket (out of scope here).
