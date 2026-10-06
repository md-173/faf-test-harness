#!/usr/bin/env bash
#
# Mint a lobby access token for one seeded account from the Hydra of a local gitops-stack brought up
# with Tilt, as local-stack.yml does before its session (WBS-2.3.3.2, #413; the recipe is the
# 2.2.14 spike's, #414). No browser and no faf-user-service: start the authorization-code flow,
# accept the login and consent challenges on Hydra's admin port the way a login provider does, then
# exchange the code. The token lasts an hour.
#
# Usage:
#   scripts/ci/mint-local-token.sh <user-id> <out-file>
#
#   scripts/ci/mint-local-token.sh 1 "$TOKENS/1.txt"
#
# <user-id> is a login.id from FAForever/db's test-data.sql. The token is written to <out-file>,
# readable by its owner only. Needs curl, jq and openssl, Hydra's public port forwarded to
# 127.0.0.1:4444 and its admin port to 127.0.0.1:4445 (kubectl port-forward -n faf-apps
# svc/ory-hydra 4444 4445), and the Tiltfile's Hydra dev mode: outside it Hydra marks its CSRF
# cookies Secure, which curl does not send over http://.
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "usage: $0 <user-id> <out-file>" >&2
    exit 2
fi
id=$1
out=$2
pub=http://hydra.faforever.localhost   # Hydra's URLS_SELF_PUBLIC; admin redirect_to URLs use it
adm=http://127.0.0.1:4445              # Hydra's admin port
cid=95ecec08-29c1-4c48-ae0a-b000ff349cb8
redirect=http://127.0.0.1

jar=$(mktemp)
chmod 600 "$jar"
trap 'rm -f "$jar"' EXIT

# Hydra's CSRF cookies are host-only, so every public call uses the one host name the redirects
# carry, connected to the forward of Hydra's public port.
pub_curl() {
    curl -fsS --max-time 10 --connect-to hydra.faforever.localhost:80:127.0.0.1:4444 \
        -b "$jar" -c "$jar" "$@"
}
param() { sed -n "s/.*[?&]$1=\([^&]*\).*/\1/p"; }
# jq -e fails on a missing field rather than printing "null", which would otherwise travel on as a
# URL here, or as the token below and fail the session at welcome, where it reads as a harness fault.
accept() {
    curl -fsS --max-time 10 -X PUT -H 'Content-Type: application/json' -d "$2" "$adm/admin/oauth2/auth/requests/$1" |
        jq -re .redirect_to
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
    -d "redirect_uri=$redirect" "$pub/oauth2/token" | jq -je .access_token > "$out"
