# A session against a local gitops-stack lobby (spike, WBS 2.2.14)

Can a harness session run against a FAF lobby deployed locally from
[FAForever/gitops-stack](https://github.com/FAForever/gitops-stack), with no credential from FAF?
This note answers the seven questions of #414 for #413, which would run such a session in CI,
and for anyone weighing a local stack against the shared `.xyz` environment.

## Verdict

**Yes, and worth pursuing for CI as #413, beside the shared-environment run rather than instead
of it.**

- A two-peer `session` passed against a local lobby four times out of four on 2026-09-28, with
  [runbook §11](../operations/harness-runbook.md#11-a-session-in-a-consumers-ci-wbs-421)'s flags
  apart from the lobby URL and the token files. No credential, account or secret came from FAF.
- Tokens come from the local Hydra in a fraction of a second each, with no browser. Seven of the
  eight seeded accounts log in, and a job that owns the database can add more.
- A cold start to a usable lobby took about 7 minutes of machine time on a developer machine,
  from an empty image cache over a home connection. FAF's own CI brings the whole stack up in
  under 3 minutes on a hosted runner.
- A local run does not cover the policy path, faf-user-service's login rules, TLS, Cloudflare,
  or what `.xyz` actually runs (see [what a local run does not cover](#what-a-local-run-does-not-cover)).
  So the local run is the repeatable one and the shared run stays the realistic one, which
  answers #411's question 6 the way #413 assumes.
- One thing still reaches FAF: the adapter's telemetry, which defaults to FAF's production
  service ([question 3 for FAF](#questions-for-faf)).

## What was run

| | |
| --- | --- |
| gitops-stack | `develop` at [`808a05f`][gs] (committed 2026-09-20, still the head on 2026-09-28) |
| Lobby server | faf-server [`v1.18.3`][srv], the tag the chart pins |
| Test data and schema | FAForever/db [`v146`][db] (`921e29a`), the tag matching the `faf-db-migrations:v146` image |
| Login service | faf-user-service [`4.2.0`][us], the tag the Tilt path deploys (not run here) |
| Harness | jars built from `d916ad8b`; faf-ice-adapter 3.3.14, the build's pin; `faf-uid` v4.0.7, runbook §11's pin |
| Tools | kind v0.31.0 (node v1.35.0, containerd 2.2.0), ctlptl v0.9.6, Tilt v0.37.7, Helm v4.3.0 (FAF's CI uses the same Helm), kubectl v1.34.1 |
| Machine | WSL2 on a 16-thread Windows machine with 15.2 GB of RAM. The WSL VM is capped at 7.5 GB and already had 3.9 GB in use by other work; Docker Desktop 29.0.1 runs its engine in the same VM |
| FAF's own CI | gitops-stack's Checks workflow, runs `35514999826` (`develop`) and `36318290606` (`main`) |

## Answers

### 1. Endpoint

The lobby's IngressRoute matches `Host(lobby.<baseDomain>) || Host(ws.<baseDomain>)` on
traefik's `websecure` entrypoint and forwards to the lobby's service on port 8003
([ingress.yaml:9][gs-ingress]). The Tiltfile adds `web` to every route that lists `websecure`
([Tiltfile:159-162][gs-tilt-web]) and port-forwards traefik to host ports 80 and 443
([Tiltfile:297][gs-tilt-traefik]), so locally the lobby is `ws://ws.faforever.localhost/`.
The server mounts its WebSocket at `/`, as [lobby-protocol-spec.md](lobby-protocol-spec.md)
records; the lobby logged `LobbyServer[WebSocket]: listening on ws://0.0.0.0:8003/`. The chart's
comment that it listens on `8003/ws` is out of date.

Two things differ on a Linux developer machine. Binding ports 80 and 443 as a normal user needs
`net.ipv4.ip_unprivileged_port_start` lowered from its default of 1024: Tilt's own forward failed
with `bind: permission denied`, without holding anything else up. And `*.faforever.localhost`
resolves only through the hosts file under `hosts: files dns`. curl resolves `*.localhost`
itself, the JVM does not.

Checked live: through `kubectl port-forward -n traefik deploy/release-name-traefik 8080:8000`, a
WebSocket upgrade got `101 Switching Protocols` on both host names and `404` without a matching
host, since traefik ignores the port when it matches `Host()`. Every harness run connected
through `ws://ws.faforever.localhost:8080`.

### 2. Transport

Plain `ws://` on traefik's `web` entrypoint. The local traefik values configure no TLS store
([values-local.yaml][gs-traefik-local]), unlike test's ([values-test.yaml:88-92][gs-traefik-test]),
so `wss://` on 443 presents traefik's generated default certificate, which names only a random
host under `traefik.default`. The JDK's WebSocket client verifies host names, so trusting that
certificate with the steps in
[runbook §3](../operations/harness-runbook.md#trusting-a-private-certificate-authority) is not
enough: `wss://` would need a certificate naming `ws.faforever.localhost`. Nothing in this spike
needs it.

Checked live: traefik served `CN = TRAEFIK DEFAULT CERT` with a single name, a random one ending
in `.traefik.default`. With that certificate in the JVM's truststore, `run` failed the
handshake (`No subject alternative DNS name matching ws.faforever.localhost found`, exit 70).

Both local entrypoints set `readTimeout: 60`, as test's `websecure` does
([values-test.yaml:46][gs-traefik-test-timeout]). An idle `run` held open through traefik for
100 s stayed connected, so the timeout does not cut an upgraded connection.

### 3. Secrets

The README's warning that many apps fail to start without Infisical
([README.MD:53][gs-readme-infisical]) is in the section on bootstrapping a real cluster with
Argo. The Tilt path renders the charts a session needs with `config/local.yaml`, which sets
`infisical-secret.enabled: false` ([local.yaml:5][gs-config-local]), and each chart's
`templates/local-secret.yaml` then supplies fixed local credentials, the lobby's among them
([local-secret.yaml][gs-lobby-secret]).

Checked live: every pod a session needs ran, and the cluster held no Infisical resource or
secret.

### 4. Tokens and accounts

Local Hydra is `oryd/hydra:v26.2.0` ([values.yaml:3][gs-hydra-values]), the tag test also
declares. It issues JWT access tokens ([config.yaml:14][gs-hydra-config]) and sets no token
lifetime, so Hydra's default of one hour applies. It registers the public client the harness
uses on `.xyz`, `95ecec08-29c1-4c48-ae0a-b000ff349cb8` ("FAF Classic Client (Python)",
[values.yaml:20-28][gs-hydra-client]): `authorization_code` and `refresh_token`, scopes including
`lobby` and `offline`, redirect `http://localhost` or `http://127.0.0.1`, no client secret.

The lobby accepts a token signed by a key in Hydra's JWKS, with `lobby` in `scp` and a numeric
`sub` ([oauth_service.py:76-100][srv-oauth]). That `sub` must then exist in `login` with no active
ban in the `lobby_ban` view ([lobbyconnection.py:633-648][srv-auth]), and the view counts only
GLOBAL bans ([V107:3187][db-lobby-ban]). The chart's `FORCE_STEAM_LINK: "true"`
([config.yaml:23][gs-lobby-config]) is read by nothing in v1.18.3, so it gates nothing.

There are two ways to get a token for a seeded account, and they reach different numbers of
accounts:

| Route | How | Seeded accounts that can log in |
| --- | --- | --- |
| faf-user-service, the real login page | Log in through a browser, then exchange the code with Hydra. The page is a Vaadin view, so this route needs a browser. | 2, from the source: `test` (id 1) and `steambie` (id 7). A login that asks for `lobby` needs a game-ownership link ([LoginService.kt:121][us-login], [HydraService.kt:73][us-hydra]), and the test data seeds one for those two only ([test-data.sql:66][db-links]). |
| Hydra's admin API | Start the code flow, accept the login and consent challenges on the admin port, as a login provider does, then exchange the code ([script below](#running-it)). No browser and no user-service. | 7 of 8, measured: all but `Rhiza` (id 3), who has a permanent GLOBAL ban ([test-data.sql:214-216][db-bans]). |

Checked live on the admin route: tokens for all eight accounts took 1.4 s together. Each was
RS256 with a `kid` in Hydra's JWKS, `scp` of `openid offline lobby`, issuer
`http://ory-hydra:4444` and a lifetime of 3,600 s. Seven accounts reached `session ready`.
Rhiza's login ended with the lobby's `You are banned from FAF forever` notice and exit 70. Five of
the seven have no ownership link, which confirms the lobby itself does not check ownership.

The test data seeds eight accounts ([test-data.sql:54-63][db-logins]). A job that owns the
database can insert more, so the seed does not cap a session's peer count.

### 5. Policy

The Tiltfile's `no_policy_server()` sets `USE_POLICY_SERVER: False` in the lobby's config
([Tiltfile:190-200][gs-tilt-policy], applied at [Tiltfile:211-212][gs-tilt-policy-apply]), and
only the policy server's ConfigMap and Secret are applied, never its Deployment
([Tiltfile:382-383][gs-tilt-policy-config]). With that flag off, `check_policy_conformity` returns
before it uses `unique_id` ([lobbyconnection.py:577][srv-policy]). So a local login accepts any
`unique_id`, and a local run no longer covers the `faf-uid` path a login on `.xyz` exercises: a
POST to the policy server that has to succeed, although its verdict is ignored at login
([lobbyconnection.py:725][srv-ignore]).

Checked live: the lobby's config held `USE_POLICY_SERVER: false`, no policy-server Deployment
existed, and every `run` logged in with `--unique-id=placeholder`. The sessions ran `faf-uid` as
§11 does (1,916 characters), and the lobby ignored it.

### 6. Fidelity

The Tilt path does not use the Argo appsets. It renders the checked-out gitops-stack commit with
`config/local.yaml` and the Tiltfile's patches. Declared, for what a session touches:

| Component | Local (Tilt) | Test (`.xyz`) | Declared in |
| --- | --- | --- | --- |
| Lobby | `faf-python-server:v1.18.3` | the same | [deployment.yaml:24][gs-lobby-image], no per-environment values |
| Lobby config | policy check off; JWKS from the in-cluster Hydra over http | policy check on; JWKS from `https://hydra.faforever.xyz` | [Tiltfile:411-414][gs-tilt-lobby], [config.yaml:13][gs-lobby-configmap] |
| Hydra | `v26.2.0`; issuer `http://ory-hydra:4444`; dev mode; no janitor | `v26.2.0`; issuer `https://hydra.faforever.xyz`; CORS for localhost | [Tiltfile:364-370][gs-tilt-hydra], [values-test.yaml][gs-hydra-test] |
| Policy server | not deployed | `faf-policy-server:v1.23` | [Tiltfile:382][gs-tilt-policy-config], [deployment.yaml:31][gs-policy-image] |
| faf-user-service | `4.2.0`, rendered with `values-prod.yaml` | `master`, a rolling tag | [Tiltfile:393][gs-tilt-user], [values-prod.yaml:2][gs-user-prod], [values-test.yaml:7][gs-user-test] |
| Schema | `faf-db-migrations:v146` | the same | [cronjob.yaml:23][gs-migrations] |
| Test data | FAForever/db `develop` at run time unless pinned; this run pinned `v146` | the environment's own long-lived database | [populate-database.sh][gs-populate] |
| Traefik | no TLS store; `web` and `websecure` both route | Cloudflare in front; a TLS store | [values-local.yaml][gs-traefik-local], [values-test.yaml:88-92][gs-traefik-test] |

The running images matched the declared tags. The lobby logged `Lobby v1.18.3 (Python 3.13.15)`
and `Database version is v146`. Their image digests began `sha256:9ef7413a43c1` for the lobby
and `sha256:ff67c7fb5f95` for Hydra.

The lobby checks neither issuer nor audience (#411), so Hydra's local issuer changes nothing for
the harness. What `.xyz` actually runs is out of reach: it needs Argo or cluster access, and the
test cluster can follow rolling tags ([README.MD:23][gs-readme-rolling]), so a declared tag is not
necessarily the running one. That is a question for FAF, below.

### 7. Cost

Developer path, measured on the machine above: a cold start with an empty image cache over a
home connection, bringing up only what a session needs (`--run faf-lobby-server --run
ory-hydra-create-client-2`: traefik, MariaDB, RabbitMQ, Postgres, Hydra, the lobby and their
setup jobs, 50 of the Tiltfile's 109 resources).

| Step | Time |
| --- | --- |
| `ctlptl create cluster kind`, including the 1.35 GB node image | 91 s |
| `tilt up` until the lobby and Hydra's client job are Ready | 285 s |
| Load the test data, then restart the lobby | 13 s and 25 s |
| **To a usable lobby** | **about 6 min 55 s** |
| Mint eight tokens | 1.4 s |
| One two-peer `session`, start to verdict | 7.6 to 10.7 s |

| Resource | Peak or total |
| --- | --- |
| Memory, the kind node | 2.5 GiB (`docker stats`) |
| Memory, the whole VM | 1.9 GB above its baseline at the busiest point (the sessions), and swap up 0.43 GB |
| Memory, one session | five short-lived JVMs; the largest process peaked at 177 MB RSS |
| CPU, the kind node | 201 core-seconds to Ready, peaking at 6.2 cores; 72 more for loading the data, restarting the lobby, the logins, four sessions and 100 s idle |
| CPU, one session | 9.0 CPU-seconds on the host for the whole process tree: five JVMs and two `faf-uid` runs |
| Disk | about 5.2 GB: the node image (1.35 GB) plus the node's volume (3.8 GB, containerd's store of the nine pulled images, 0.99 GB compressed, and kind's own) |

The checkout sat under `/tmp`, which kind mounts as tmpfs inside the node, so the databases'
files (244 MB) counted as memory rather than disk. The Tiltfile builds no images and sets up no
live update, so `tilt up` and `tilt ci` start the same pods, apart from a config reloader it skips
when `CI` is set ([Tiltfile:265-267][gs-tilt-reloader]).

For comparison, FAF's own CI brings the whole stack up with `tilt ci` into kind on a
public-repo hosted runner (4 vCPU, 16 GB; a private repository's runner has 2 and 8 GB), in the
`docker/tilt` image:

| Run | Branch | Date | Lobby listening | All workloads healthy | Free disk reported by RabbitMQ |
| --- | --- | --- | --- | --- | --- |
| `35514999826` | `develop` | 2026-09-20 | 2 min 21 s | 2 min 53 s | 87.8 GB |
| `36318290606` | `main` | 2026-09-27 | 2 min 8 s | 2 min 57 s | 85.7 GB |

Times run from the start of `tilt ci`; creating the kind cluster took about 20 s more. The 14 GB
of SSD that GitHub documents for this runner is therefore not the constraint it looks like. The
logs show time and disk only, not memory or CPU, so they show that the whole stack fits, not how
much room it leaves. These runs load no test data and mint no tokens: `populate-db` is a manual
resource ([Tiltfile:257][gs-tilt-populate]) that `tilt ci` never triggers.

## Running it

Needs Docker, kind, ctlptl, Tilt, Helm, kubectl, curl, jq and openssl. On a Linux host, raise
the inotify limits kind documents (`fs.inotify.max_user_instances=512`,
`fs.inotify.max_user_watches=524288`) and add the host names to `/etc/hosts`:

```bash
echo '127.0.0.1 ws.faforever.localhost lobby.faforever.localhost hydra.faforever.localhost' | sudo tee -a /etc/hosts
```

From a gitops-stack checkout at the pinned commit:

```bash
ctlptl create cluster kind --registry=ctlptl-registry
tilt up -- --run faf-lobby-server --run ory-hydra-create-client-2
# In another shell, once `tilt get uiresource faf-lobby-server` answers:
tilt wait --for=condition=Ready --timeout=20m uiresource/faf-lobby-server uiresource/ory-hydra-create-client-2

# `tilt trigger populate-db` fails on Linux (see the questions for FAF), so run its pipeline,
# pinned to the FAForever/db tag that matches the migrations image.
curl -fsS https://raw.githubusercontent.com/FAForever/db/v146/test-data.sql |
    kubectl exec -i -n faf-infra statefulset/mariadb -- mariadb --host=mariadb --user=root --password=banana faf_lobby
# The lobby reads its next game id once, at startup, so restart it after loading the data.
kubectl rollout restart deployment/faf-lobby-server -n faf-apps
kubectl rollout status deployment/faf-lobby-server -n faf-apps

# Tilt's own forward of ports 80 and 443 needs privileges; this one does not. Leave it running.
kubectl port-forward -n traefik deploy/release-name-traefik 8080:8000
```

Tokens, one per peer, with this script (the ids are `login.id` values from the test data):

```bash
#!/usr/bin/env bash
# mint-local-token.sh <user-id> <out-file>
#
# Mints a lobby access token for a seeded account from the local Hydra, with no browser and no
# faf-user-service: start the authorization-code flow, accept the login and consent challenges on
# Hydra's admin port the way a login provider does, then exchange the code. The token goes to
# <out-file> (0600).
set -euo pipefail
id=$1
out=$2
pub=http://hydra.faforever.localhost   # Hydra's URLS_SELF_PUBLIC; every redirect points here
adm=http://127.0.0.1:4445              # Hydra's admin port, forwarded by Tilt
cid=95ecec08-29c1-4c48-ae0a-b000ff349cb8
redirect=http://127.0.0.1

jar=$(mktemp)
chmod 600 "$jar"
trap 'rm -f "$jar"' EXIT

# Hydra's CSRF cookies are host-only, so every public call uses the one host name the redirects
# carry, connected to Tilt's forward of Hydra's public port.
pub_curl() {
    curl -fsS --max-time 10 --connect-to hydra.faforever.localhost:80:127.0.0.1:4444 \
        -b "$jar" -c "$jar" "$@"
}
param() { sed -n "s/.*[?&]$1=\([^&]*\).*/\1/p"; }
accept() {
    curl -fsS --max-time 10 -X PUT -H 'Content-Type: application/json' -d "$2" "$adm/admin/oauth2/auth/requests/$1" |
        jq -r .redirect_to
}

state=$(openssl rand -hex 16)
location=$(pub_curl -o /dev/null -w '%{redirect_url}' \
    "$pub/oauth2/auth?client_id=$cid&response_type=code&redirect_uri=$redirect&scope=openid+offline+lobby&state=$state")
next=$(accept "login/accept?login_challenge=$(param login_challenge <<<"$location")" "{\"subject\":\"$id\"}")
location=$(pub_curl -o /dev/null -w '%{redirect_url}' "$next")
next=$(accept "consent/accept?consent_challenge=$(param consent_challenge <<<"$location")" \
    '{"grant_scope":["openid","offline","lobby"]}')
location=$(pub_curl -o /dev/null -w '%{redirect_url}' "$next")
code=$(param code <<<"$location")

umask 077
pub_curl --data-urlencode "code=$code" -d grant_type=authorization_code -d "client_id=$cid" \
    -d "redirect_uri=$redirect" "$pub/oauth2/token" | jq -j .access_token > "$out"
```

Then runbook §11's session, pointed at the local lobby:

```bash
java -jar mock-client-<version>-all.jar \
  --lobby-websocket-url=ws://ws.faforever.localhost:8080 \
  --uid-binary-path=./faf-uid \
  --ice-adapter-binary-path=./faf-ice-adapter.jar \
  --mock-game-binary-path=./mock-game-<version>-all.jar \
  session \
    --peers=2 \
    --peer-access-token-file=host.jwt \
    --peer-access-token-file=joiner.jwt
```

`--uid-binary-path` can be `--unique-id=placeholder` here, since the policy check is off. To tear
down: `ctlptl delete cluster kind` and `ctlptl delete registry ctlptl-registry`.

### What a passing session looked like

Session 1 of 4, with `test` (id 1) hosting and `steambie` (id 7) joining, trimmed to the
checkpoints:

```text
[21:05:07.414] [A] lobby WebSocket connected: ws://ws.faforever.localhost:8080
[21:05:07.610] [A] session ready: id=1 login=test
[21:05:08.564] [A] game launch: uid=2 mod=faf name=faf-test-harness session d062bbb6-...
[21:05:09.544] [A] state entry: HOSTING
[21:05:09.656] [B] session ready: id=7 login=steambie
[21:05:09.656] [B] Sending game_join for uid=2
[21:05:11.391] [A] peer connect: login=steambie id=7 offer=true
[21:05:13.943] [B] peer connected: local=7 remote=1 connected=true
[21:05:13.956] [A] peer connected: local=1 remote=7 connected=true
[21:05:17.133] session: PASS - 2 peers, full mesh and two-way game traffic, nothing left running
```

Game ids started at 2 because the seeded `game_stats` row is id 1, which the restarted lobby had
read.

## What #413 inherits

- **Bring-up.** `tilt ci -- --run faf-lobby-server --run ory-hydra-create-client-2` follows FAF's
  own `checks.yml`. The Tiltfile's patches (policy check off, in-cluster JWKS, Hydra's local URLs,
  the `web` entrypoint) are what make the stack usable locally, so rendering the charts with Helm
  instead means reproducing them.
- **The lobby's address.** `tilt ci` exits once everything is Ready and takes its port-forwards
  with it, so the job holds its own `kubectl port-forward` to traefik for the session, as above.
- **Data and tokens.** Load the pinned test data with the pipeline above, not `tilt trigger`, then
  restart the lobby. Mint tokens in the job with the script. They last an hour, and minting takes a
  fraction of a second, so the job mints per run and token lifetime stops mattering.
- **Pins.** The gitops-stack commit, the FAForever/db tag that matches the migrations image, and
  the tool versions.
- **Measurements this note cannot give.** The stack and the session together on a hosted runner,
  including headroom on a private repository's 8 GB runner, and the job's own flake rate. Four
  passes on one machine are not a flake rate.
- **#413's own text** expects disk to be the ceiling and names "whatever the policy check needs"
  among the services. Neither holds: the session's images come to about 1 GB compressed, a hosted
  runner showed about 85 GB free, and the policy check is off, with the policy server not
  deployed.

## Questions for FAF

1. Which versions `.xyz` runs, beside what `develop` declares.
2. `tilt/scripts/populate-database.sh` is committed without its execute bit (mode `100644`), so
   `tilt trigger populate-db` fails on Linux and macOS. FAF's CI never triggers it, so it stays
   green.
3. Telemetry. faf-ice-adapter 3.3.14 defaults `--telemetry-server` to
   `wss://ice-telemetry.faforever.com`, and the harness passes no override on `main`, so a run
   against a local lobby still reports to FAF's production telemetry (#458 moves the harness to
   `.xyz`). In this run each adapter's telemetry socket kept opening and being closed by the
   server with `Internal Error` within about a second, for the whole session, so events for local
   game ids 2 to 5 may have reached it. Is there a target FAF wants a local run to use?

## What a local run does not cover

- The policy path ([question 5](#5-policy)).
- faf-user-service's login rules, when tokens come from Hydra's admin API.
- `wss://`, Cloudflare and the real certificate chain.
- Real accounts, other players, and whatever `.xyz` runs beyond its declared tags.

[gs]: https://github.com/FAForever/gitops-stack/tree/808a05fadf21989f00b444c43e649846c7d80957
[gs-ingress]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-lobby-server/templates/ingress.yaml#L9
[gs-tilt-web]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L159-L162
[gs-tilt-traefik]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L297
[gs-traefik-local]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/cluster/traefik/values-local.yaml
[gs-traefik-test]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/cluster/traefik/values-test.yaml#L88-L92
[gs-traefik-test-timeout]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/cluster/traefik/values-test.yaml#L46
[gs-readme-infisical]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/README.MD?plain=1#L53
[gs-readme-rolling]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/README.MD?plain=1#L23
[gs-config-local]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/config/local.yaml#L5
[gs-lobby-secret]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-lobby-server/templates/local-secret.yaml
[gs-hydra-values]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/ory-hydra/values.yaml#L3
[gs-hydra-config]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/ory-hydra/templates/config.yaml#L14
[gs-hydra-client]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/ory-hydra/values.yaml#L20-L28
[gs-hydra-test]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/ory-hydra/values-test.yaml
[gs-lobby-config]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-lobby-server/config/config.yaml#L23
[gs-lobby-configmap]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-lobby-server/templates/config.yaml#L13
[gs-lobby-image]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-lobby-server/templates/deployment.yaml#L24
[gs-tilt-policy]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L190-L200
[gs-tilt-policy-apply]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L211-L212
[gs-tilt-policy-config]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L382-L383
[gs-tilt-lobby]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L411-L414
[gs-tilt-hydra]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L364-L370
[gs-tilt-user]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L393
[gs-tilt-populate]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L257
[gs-tilt-reloader]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/Tiltfile#L265-L267
[gs-policy-image]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-policy-server/templates/deployment.yaml#L31
[gs-user-prod]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-user-service/values-prod.yaml#L2
[gs-user-test]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-user-service/values-test.yaml#L7
[gs-migrations]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/apps/faf-db-migrations/templates/cronjob.yaml#L23
[gs-populate]: https://github.com/FAForever/gitops-stack/blob/808a05fadf21989f00b444c43e649846c7d80957/tilt/scripts/populate-database.sh
[srv]: https://github.com/FAForever/server/tree/v1.18.3
[srv-oauth]: https://github.com/FAForever/server/blob/v1.18.3/server/oauth_service.py#L76-L100
[srv-auth]: https://github.com/FAForever/server/blob/v1.18.3/server/lobbyconnection.py#L633-L648
[srv-policy]: https://github.com/FAForever/server/blob/v1.18.3/server/lobbyconnection.py#L577
[srv-ignore]: https://github.com/FAForever/server/blob/v1.18.3/server/lobbyconnection.py#L725
[db]: https://github.com/FAForever/db/tree/v146
[db-logins]: https://github.com/FAForever/db/blob/v146/test-data.sql#L54-L63
[db-links]: https://github.com/FAForever/db/blob/v146/test-data.sql#L66
[db-bans]: https://github.com/FAForever/db/blob/v146/test-data.sql#L214-L216
[db-lobby-ban]: https://github.com/FAForever/db/blob/v146/migrations/V107__baseline_schema.sql#L3187
[us]: https://github.com/FAForever/faf-user-service/tree/4.2.0
[us-login]: https://github.com/FAForever/faf-user-service/blob/4.2.0/src/main/kotlin/com/faforever/userservice/backend/account/LoginService.kt#L121
[us-hydra]: https://github.com/FAForever/faf-user-service/blob/4.2.0/src/main/kotlin/com/faforever/userservice/backend/hydra/HydraService.kt#L73
