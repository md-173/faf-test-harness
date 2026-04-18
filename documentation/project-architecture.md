**libraries for logging, JSON parsing, and testing** | 

| Category | Library | Why |
|---|---|---|
| **Logging API** | `org.slf4j:slf4j-api` | Same as ICE adapter; standard Java logging api|
| **Logging Impl** | `ch.qos.logback:logback-classic` | Same as ICE adapter; includes `logback-core` transitively |
| **JSON parsing** | `com.fasterxml.jackson.core:jackson-databind` | Same as ICE adapter; need it for lobby protocol JSON and JSON-RPC |
| **Testing** | `org.junit.jupiter:junit-jupiter` | Already in place |

These match the ICE adapter exactly, which avoids compatibility issues when we launch it as a subprocess.