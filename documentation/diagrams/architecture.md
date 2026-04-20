```mermaid
flowchart LR
    %% ---------- styles ----------
    classDef mock     fill:#ffe7b3,stroke:#d98c00,stroke-width:2px,color:#000
    classDef real     fill:#cfe6ff,stroke:#1f6feb,stroke-width:2px,color:#000
    classDef external fill:#e7e0ff,stroke:#6f42c1,stroke-width:2px,color:#000

    %% ---------- nodes ----------
    subgraph FAF["FAF Infrastructure (real, external)"]
        HYDRA["Ory Hydra<br/>OAuth2 / OIDC"]
        LS["FAF Lobby Server<br/>ws_bridge_rs + SimpleJsonProtocol"]
    end

    subgraph LOCAL["Local Player Node"]
        MC["Mock Client [MOCK]<br/>Java orchestrator / FSM"]
        IA["faf-ice-adapter [REAL]<br/>launched as subprocess"]
        MG["Mock Game [MOCK]<br/>FA stand-in"]
    end

    subgraph PEER["Peer Player Node (symmetric)"]
        PMC["Peer Mock Client [MOCK]"]
        PIA["Peer faf-ice-adapter [REAL]"]
        PMG["Peer Mock Game [MOCK]"]
    end

    subgraph LEGEND["Legend"]
        L1["MOCK component"]
        L2["REAL component (reused)"]
        L3["External service"]
    end

    %% ---------- edges ----------
    MC  -->|"HTTPS OAuth2 Auth Code flow"|                         HYDRA
    MC  <-->|"JSON over TCP (WebSocket/WSS via ws_bridge_rs)"|     LS
    MC  <-->|"JSON-RPC over TCP (loopback)"|                       IA
    MG  <-->|"GPGNet: custom binary over TCP (loopback)"|          IA
    IA  <==>|"UDP peer-to-peer (NAT-traversed game traffic)"|      PIA
    PMC <-->|"JSON over TCP (WS/WSS)"|                             LS
    PMC <-->|"JSON-RPC over TCP"|                                  PIA
    PMG <-->|"GPGNet binary over TCP"|                             PIA

    %% ---------- class assignments ----------
    class MC,MG,PMC,PMG,L1 mock
    class IA,LS,PIA,L2     real
    class HYDRA,L3         external

    %% ---------- subgraph tinting ----------
    style FAF    fill:#f4efff,stroke:#6f42c1,stroke-width:1px
    style LOCAL  fill:#fff7e6,stroke:#d98c00,stroke-width:1px
    style PEER   fill:#f0f4ff,stroke:#1f6feb,stroke-width:1px
    style LEGEND fill:#fafafa,stroke:#999999,stroke-width:1px
```