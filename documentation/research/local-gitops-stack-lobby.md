# A session against a local gitops-stack lobby (spike, WBS 2.2.14)

Can a harness session run against a FAF lobby deployed locally from
[FAForever/gitops-stack](https://github.com/FAForever/gitops-stack), with no credential from FAF?
This note answers the seven questions of #414 for #413, which would run such a session in CI,
and for anyone weighing a local stack against the shared `.xyz` environment.

## Status

Repo reading done on 2026-09-28 against the commits below. The live bring-up has not run yet,
so every answer here is what the repositories establish, and "to confirm live" marks what the
bring-up still has to check.

| Source | Read at |
| --- | --- |
| gitops-stack | `develop` at [`808a05f`][gs] (committed 2026-09-20) |
| Lobby server | faf-server [`v1.18.3`][srv], the tag the chart pins |
| Test data and schema | FAForever/db [`v146`][db] (`921e29a`), the tag matching the `faf-db-migrations:v146` image |
| Login service | faf-user-service [`4.2.0`][us], the tag the Tilt path deploys |
| ICE adapter | faf-ice-adapter 3.3.14, the harness's pinned release |
| FAF's own CI | gitops-stack's Checks workflow, runs `35514999826` (`develop`) and `36318290606` (`main`) |

## Answers

### 1. Endpoint

The lobby's IngressRoute matches `Host(lobby.<baseDomain>) || Host(ws.<baseDomain>)` on
traefik's `websecure` entrypoint and forwards to the lobby's service on port 8003
([ingress.yaml:9][gs-ingress]). The Tiltfile adds `web` to every route that lists `websecure`
([Tiltfile:159-162][gs-tilt-web]) and port-forwards traefik to host ports 80 and 443
([Tiltfile:297][gs-tilt-traefik]), so locally the lobby is `ws://ws.faforever.localhost/`.
The server mounts its WebSocket at `/`, as [lobby-protocol-spec.md](lobby-protocol-spec.md)
records: FAF's CI logs `LobbyServer[WebSocket]: listening on ws://0.0.0.0:8003/`. The chart's
comment that it listens on `8003/ws` is out of date.

Two things differ on a Linux developer machine. Binding ports 80 and 443 as a normal user needs
`net.ipv4.ip_unprivileged_port_start` lowered from its default of 1024, so Tilt's own forward
fails there. And `*.faforever.localhost` resolves only through the hosts file under
`hosts: files dns`: curl resolves `*.localhost` itself, the JVM does not.

To confirm live: a WebSocket upgrade through traefik on both host names, and the harness
connecting through it.

### 2. Transport

Plain `ws://` on traefik's `web` entrypoint. The local traefik values configure no TLS store
([values-local.yaml][gs-traefik-local]), unlike test's ([values-test.yaml:88-92][gs-traefik-test]),
so `wss://` on 443 presents traefik's generated default certificate, which names only a random
host under `traefik.default`. The JDK's WebSocket client verifies host names, so trusting that
certificate with the steps in
[runbook §3](../operations/harness-runbook.md#trusting-a-private-certificate-authority) is not
enough: `wss://` would need a certificate naming `ws.faforever.localhost`. Nothing in this spike
needs it.

Both local entrypoints set `readTimeout: 60`, as test's `websecure` does
([values-test.yaml:46][gs-traefik-test-timeout]), so a session held open through traefik for
longer than a minute is part of what the live run checks.

### 3. Secrets

The README's warning that many apps fail to start without Infisical
([README.MD:53][gs-readme-infisical]) is in the section on bootstrapping a real cluster with
Argo. The Tilt path renders the charts with `config/local.yaml`, which sets
`infisical-secret.enabled: false` ([local.yaml:5][gs-config-local]), and each chart's
`templates/local-secret.yaml` then supplies fixed local credentials, the lobby's among them
([local-secret.yaml][gs-lobby-secret]). FAF's CI runs start every workload with no Infisical
identity, so the stop condition "the local path cannot supply what Infisical provides" cannot
fire.

To confirm live: every pod a session needs is running, and no Infisical resource exists.

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
| faf-user-service, the real login page | Log in through a browser, then exchange the code with Hydra. The page is a Vaadin view, so this route needs a browser. | 2: `test` (id 1) and `steambie` (id 7). A login that asks for `lobby` needs a game-ownership link ([LoginService.kt:121][us-login], [HydraService.kt:73][us-hydra]), and the test data seeds one for those two only ([test-data.sql:66][db-links]). |
| Hydra's admin API | Start the code flow, accept the login and consent challenges on the admin port, as a login provider does, then exchange the code. No browser and no user-service. | 7 of 8: all but `Rhiza` (id 3), who has a permanent GLOBAL ban ([test-data.sql:214-216][db-bans]). |

The test data seeds eight accounts ([test-data.sql:54-63][db-logins]). A job that owns the
database can insert more, so the seed does not cap a session's peer count.

To confirm live: minting on the admin route, and which seeded accounts reach `session ready`.

### 5. Policy

The Tiltfile's `no_policy_server()` sets `USE_POLICY_SERVER: False` in the lobby's config
([Tiltfile:190-200][gs-tilt-policy], applied at [Tiltfile:211-212][gs-tilt-policy-apply]), and
only the policy server's ConfigMap and Secret are applied, never its Deployment
([Tiltfile:382-383][gs-tilt-policy-config]). With that flag off, `check_policy_conformity` returns
before it uses `unique_id` ([lobbyconnection.py:577][srv-policy]). So a local login accepts any
`unique_id`, and a local run no longer covers the `faf-uid` path a login on `.xyz` exercises: a
POST to the policy server that has to succeed, although its verdict is ignored at login
([lobbyconnection.py:725][srv-ignore]).

To confirm live: a login with a placeholder `--unique-id`.

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
| Test data | FAForever/db `develop` at run time, unpinned | the environment's own long-lived database | [populate-database.sh][gs-populate] |
| Traefik | no TLS store; `web` and `websecure` both route | Cloudflare in front; a TLS store | [values-local.yaml][gs-traefik-local], [values-test.yaml:88-92][gs-traefik-test] |

The lobby checks neither issuer nor audience (#411), so Hydra's local issuer changes nothing for
the harness. What `.xyz` actually runs is out of reach: it needs Argo or cluster access, and the
test cluster can follow rolling tags ([README.MD:23][gs-readme-rolling]), so a declared tag is not
necessarily the running one. That is a question for FAF, below.

### 7. Cost

The developer-path figures (start-up time to a usable lobby, peak memory and CPU, disk) come
from the live run.

For comparison, FAF's own CI brings the whole stack up with `tilt ci` into kind on a
public-repo hosted runner (4 vCPU, 16 GB), in the `docker/tilt` image:

| Run | Branch | Date | Lobby listening | All workloads healthy | Free disk reported by RabbitMQ |
| --- | --- | --- | --- | --- | --- |
| `35514999826` | `develop` | 2026-09-20 | 2 min 21 s | 2 min 53 s | 87.8 GB |
| `36318290606` | `main` | 2026-09-27 | 2 min 8 s | 2 min 57 s | 85.7 GB |

Times run from the start of `tilt ci`; creating the kind cluster took about 20 s more. The 14 GB
of SSD that GitHub documents for this runner is therefore not the constraint it looks like. The
logs show time and disk only, not memory or CPU, so they show that the stack fits, not how much
room it leaves. These runs load no test data and mint no tokens: `populate-db` is a manual
resource ([Tiltfile:257][gs-tilt-populate]) that `tilt ci` never triggers.

The Tiltfile builds no images and sets up no live update, so `tilt up` and `tilt ci` start the
same pods, apart from a config reloader it skips when `CI` is set
([Tiltfile:265-267][gs-tilt-reloader]).

## Questions for FAF

1. Which versions `.xyz` runs, beside what `develop` declares.
2. `tilt/scripts/populate-database.sh` is committed without its execute bit (mode `100644`), so
   `tilt trigger populate-db` fails on Linux and macOS. FAF's CI never triggers it, so it stays
   green.
3. Telemetry. faf-ice-adapter 3.3.14 defaults `--telemetry-server` to
   `wss://ice-telemetry.faforever.com`, and the harness passes no override on `main`, so a run
   against a local lobby still reports to FAF's production telemetry (#458 moves the harness to
   `.xyz`). Is there a target FAF wants a local run to use?

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
