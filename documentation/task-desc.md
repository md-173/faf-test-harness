### Phase 2: Requirements and Domain Research

### **Requirements & Domain Research**
These tasks involve extracting blueprints from existing documentation to unblock architectural and development phases.

* **Lobby Protocol:** Map the REST/WebSocket communication flows between the FAF Client and Lobby Server to accurately mock the authentication and matchmaking sequence.
* **GPGNet Framing Format:** Extract the byte-level TCP frame structure (headers, opcodes, payloads) so the system can successfully parse and proxy binary streams between the Game Engine and ICE Adapter.
* **JSON-RPC:** Research the JSON-RPC standard and payload structures required for the orchestrator to communicate with the Java ICE Adapter over local sockets.
* **~~Deterministic Lockstep Model~~ *(Descoped/Completed)*:** Investigate the peer-to-peer synchronization mechanism used by the game engine to manage simulation ticks and state consistency.
* **FAF Component Overview Diagrams:** Create visual blueprints defining system boundaries, data flows, and protocols between the Mock Client, FAF Lobby, ICE Adapter, and Game Engine.
* **Finite State Machine (FSM):** Architect the core lifecycle logic, defining standard state transitions (boot to teardown) and explicit recovery routes for network or subprocess failures.
* **Docker Networking Configurations:** Determine the exact TCP/UDP port mapping and bridging rules required for the containerized components to interact with external servers and local processes.
* **Linux Network Fault Injection:** Investigate OS-level Linux utilities (e.g., `tc`, `iptables`, `netem`) required to deliberately drop, delay, or corrupt packets for robust fault testing.
* **Subprocess Orchestration & IPC:** Define the methods and system calls the orchestrator will use to reliably launch, monitor, and cleanly terminate the Java ICE Adapter child process.

---

### **Project Framework**
These tasks involve building the structural and environmental foundation required before active feature coding begins.

* **Team Dev Conventions Document:** Establish team-wide repository standards, including branch naming conventions, commit message structures, and Pull Request approval checklists.
* **Shared Project Architecture:** Initialize the base Git repository and folder/module structure based on the architectural component diagrams.
* **Automated Code Verification (CI):** Deploy a GitHub Actions pipeline to automatically enforce linting, compile the codebase, and run unit tests on every Pull Request to protect the `main` branch.
* **Local Testing Environment:** Document the run configurations, IDE profiles, and environment variables necessary for developers to execute and test the orchestrator and subprocesses locally.
* **Docker Workspace:** Construct the foundational `Dockerfile` and `docker-compose.yml`, ensuring the container includes the required runtime environments (Java/Python) and fault-injection OS utilities.
* **Logging Framework:** Implement a centralized logging standard to clearly format, separate, and capture terminal output from both the main orchestrator application and its child subprocesses.
* **State Transition Controller Base:** Write the foundational boilerplate code (interfaces, enums, base classes) that translates the FSM research into the actual state management engine.

### Phase 3 & 4: Implementation and Testing Task Breakdown

This document provides high-level technical descriptions for the development, simulation, and testing tasks outlined in the execution plan. These serve as the foundation for the upcoming sprint backlogs.

---

## 1. Development

### 1.1 Mock Client Core
The orchestrator responsible for interfacing with the FAF infrastructure and managing local subprocesses.

* **Lobby Comms & Matchmaking:** Implement the WebSocket client to handle OAuth JWT authentication, process the `welcome` state sync, and navigate the matchmaking queue state machine.
* **Subprocess Execution Controller:** Utilize Java `ProcessBuilder` to securely launch, monitor the health of, and cleanly terminate the external `faf-ice-adapter` binary.
* **Ice Adapter Relay:** Implement the JSON-RPC interface over local TCP to exchange configuration data, STUN/TURN server details, and connection commands with the active ICE adapter.
* **Central Event Loop:** The primary Finite State Machine (FSM) that dictates the Mock Client's lifecycle, reacting to lobby events, relay signals, and subprocess health metrics.

### 1.2 Mock Game Core
The lightweight Java application simulating the network behavior of the actual Forged Alliance executable.

* **CLI Argument Parser:** Parse the strict launch arguments passed by the Mock Client upon game boot, extracting required ports, player IDs, and session parameters.
* **GPGNet TCP Interface:** Implement the byte-level GPGNet framing protocol to establish the required bidirectional communication channel with the ICE adapter.
* **Mock Game Heartbeat Timer:** Generate deterministic simulation ticks and network pings to satisfy the ICE adapter's keep-alive requirements and maintain the session state.
* **Runtime Lifecycle Controller:** Manage the internal state of the mock game, ensuring proper initialization, continuous lockstep simulation behavior, and clean teardown upon match completion.

---

## 2. End-to-End Simulation
Integration phases to deploy the mock environment and validate distributed P2P connectivity.

### 2.1 Containerized Node Deployment
* **Docker Networking Setup:** Configure container networking rules to expose the correct UDP/TCP ports, allowing the isolated ICE adapters to negotiate connections externally.
* **Peer Discovery Verification:** Validate that multiple containerized nodes can successfully exchange ICE candidates, traverse NAT (simulated or real), and establish direct WebRTC data channels.

### 2.2 Test Harness & Connectivity
* **Distributed Test Harness:** Scale the deployment to instantiate multiple concurrent nodes (e.g., 4-8 players) to simulate a complete, multi-client custom lobby and matchmaking flow.
* **System Stability Test:** Execute extended runtime simulations to validate memory management, persistent WebSocket stability, and FSM robustness under continuous load.

---

## 3. Fault Injection Testing
Validation of the system's "Secure by Design" recovery and teardown routes under adverse conditions.

* **Network Fault Injection:** Utilize Linux OS-level utilities (e.g., `tc`, `iptables`, `netem`) within the Docker containers to introduce artificial latency, packet loss, and connection drops during active sessions.
* **Application Fault Injection:** Deliberately crash subprocesses (e.g., sending `SIGKILL` to the `faf-ice-adapter` or Mock Game) to verify the Mock Client accurately detects the failure, reports the error to the Lobby, and executes a clean environment teardown.