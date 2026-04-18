

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