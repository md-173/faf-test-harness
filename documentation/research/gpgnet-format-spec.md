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
| [anykey111/fa-mp-test](https://github.com/anykey111/fa-mp-test) | Game-side C struct offsets; consulted only for ground-truth tie-breaking. |

When the Java codec and the Go codec disagree on the wire (see [§4.4](#section-4-4-known-issues)), this document treats the **Java codec as authoritative for what the Mock Game must produce and accept**, because the Mock Game pairs with the Java adapter in the test harness.

---

<a id="section-2-frame-grammar"></a>
## 2. Frame Grammar

The Go codec's `docs/gpgnet.md` provides the only published BNF. Reproduced verbatim:

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

The grammar above is **internally inconsistent with both reference implementations** in two ways. The implementation behaviour, not the BNF, is normative (see [§4.4](#section-4-4-known-issues)):

- **Signedness.** The BNF declares `uint32` for the `size` and `int chunk` fields. Both Java (`LittleEndianDataInputStream.readInt()`, returning `int`) and Go (`binary.Read(..., &x int32, ...)`) actually treat these fields as **signed `int32`**. Java's `MAX_CHUNK_SIZE = 10` guard makes practical signedness moot, but the value is signed on the wire.
- **Endianness scope.** The BNF says little-endian "affects only `uint32` fragments". In the implementations, **every multi-byte numeric field** (length prefixes, int chunks) is little-endian. The single byte type tag is endianness-irrelevant.

### 2.1 Plain-English description

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

A complete `GameState` frame with arg `"Lobby"` is 26 bytes:

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

A `CreateLobby(0, 6112, "TestPlayer", 1234, 1)` frame is 47 bytes:

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
| `GameOption` | `key:string, value:string` | Lobby | [`cmd_game_option.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_game_option.go) |
| `PlayerOption` | `player_id:int, key:string, value:string` | Lobby | [game-state-machine.md](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) (no typed Go struct) |
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
| `Disconnected` | `...args` (logged only by adapter) | LIVE | game-state-machine.md |
| `Rehost` | `...args` (marked unused by spec) | Lobby | game-state-machine.md |

### 7.2 Adapter → Game (Mock Game receives)

| Command | Args (ordered) | Class | Source |
|---|---|---|---|
| `CreateLobby` | `init_mode:int, port:int, login:string, player_id:int, unknown:int (= 1)` | **Lifecycle** | [`cmd_create_loby.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_create_loby.go), [`GPGNetServer.java#L111-L117`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L111-L117) |
| `HostGame` | `map_name:string` | Lifecycle | [`cmd_host_game.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_host_game.go) |
| `JoinGame` | `net_address:string, remote_player_login:string, remote_player_id:int` | Lifecycle | [`cmd_join_game.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_join_game.go) (see [§7.3](#section-7-3-arg-discrepancy)) |
| `ConnectToPeer` | `net_address:string, remote_player_login:string, remote_player_id:int` | Lifecycle | [`cmd_connect_to_peer.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_connect_to_peer.go) (see [§7.3](#section-7-3-arg-discrepancy)) |
| `DisconnectFromPeer` | `remote_player_id:int` | Lifecycle | [`cmd_disconnect_from_peer.go`](https://github.com/FAForever/faf-pioneer/blob/master/gpgnet/cmd_disconnect_from_peer.go) |
| `IceMsg` | `sender_id:int, ice_msg_json:string` | ICE | game-state-machine.md |

`CreateLobby` is the only adapter→game frame the Java adapter sends from inside `GPGNetServer.java` directly (in response to `GameState("Idle")`, [`GPGNetServer.java#L106-L117`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L106-L117)). The other adapter→game frames originate elsewhere in the adapter (RPC handlers) and are dispatched via [`GPGNetServer.sendGpgnetMessage` (line 137)](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L137) → [`gpgnetOut.writeMessage` (line 140)](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L140).

### 7.3 `JoinGame` / `ConnectToPeer` arg-count discrepancy <a id="section-7-3-arg-discrepancy"></a>

The two reference docs disagree on argument count and types:

| Source | `JoinGame` | `ConnectToPeer` |
|---|---|---|
| [faf-pioneer `gpgnet/`](https://github.com/FAForever/faf-pioneer/tree/master/gpgnet) typed structs | 3 args: `net_address:string, remote_player_login:string, remote_player_id:int` | 3 args: `net_address:string, remote_player_login:string, remote_player_id:int` |
| [faf-api-specs game-state-machine.md](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) (Server → FA table) | 2 args: `player_name, player_uid` | 3 args: `player_name, player_uid, offer:bool` |

**Resolution.** The api-specs table describes the **server → adapter** payload (what arrives over the lobby WebSocket inside `target: "game"`). The Go/Java codec structs describe the **adapter → game** payload (what the adapter rewrites and emits over the local GPGNet TCP socket). The adapter resolves the peer's `net_address` (the ICE-negotiated UDP endpoint) and substitutes it into the first arg before forwarding to FA. For `ConnectToPeer`, the `offer:bool` from the lobby is consumed by the adapter (used to decide ICE initiator role) and is **not passed through** to the game.

This spec is authoritative for the **local TCP wire** form: **3 args, types as in the faf-pioneer Go structs**, for both commands.

### 7.4 Bottleneck / BottleneckCleared <a id="section-7-4-bottleneck"></a>

Both commands flow exclusively **Game → Adapter**. Neither codec switches on them: the Java adapter's `processGpgnetMessage` falls through to the `default` arm at [`GPGNetServer.java#L129-L133`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L129-L133) and forwards opaquely via `rpcService.onGpgNetMessageReceived(command, args)`; the Go adapter likewise has no typed struct in `gpgnet/`. They are pass-through to the lobby server.

| Command | Direction | Args | Phase | Behaviour |
|---|---|---|---|---|
| `Bottleneck` | G→A→server | `code:int, ...args` (variadic; subsequent arg types not authoritatively typed anywhere — see below) | LIVE | FA reports a simulation-pipeline stall. |
| `BottleneckCleared` | G→A→server | _(none)_ | LIVE | FA reports the stall has resolved. |

**Inner arg types of `Bottleneck` are ambiguous.** The api-specs table lists the signature as `code, ...args` without naming the trailing args' types. Neither codec has a typed struct that pins them down. The C-side ground truth ([anykey111/fa-mp-test](https://github.com/anykey111/fa-mp-test)) does not enumerate them either. **Mock Game policy:** emit `Bottleneck` with only the `code:int` arg. Parser policy: surface the trailing chunks as raw chunk records (preserving tag + payload) without claiming to type them.

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

### Sources

- [`game-state-machine.md`](https://github.com/FAForever/faf-api-specs/blob/main/lobby-to-client/game-state-machine.md) — phase definitions and `GameState` value list
- [`GPGNetServer.java#L104-L122`](https://github.com/FAForever/java-ice-adapter/blob/master/ice-adapter/src/main/java/com/faforever/iceadapter/gpgnet/GPGNetServer.java#L104-L122) — Idle → CreateLobby trigger

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
- Game-side C struct offsets (consulted): [anykey111/fa-mp-test](https://github.com/anykey111/fa-mp-test)
- Sibling spec: [`lobby-protocol-spec.md`](lobby-protocol-spec.md) — JSON-wrapped GPGNet over the lobby WebSocket (out of scope here).
