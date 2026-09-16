#!/usr/bin/env bash
#
# Mint a Hydra access token from a refresh token and store it as a GitHub Actions secret
# (WBS-2.3.3.1, #364).
#
# The live integration workflow's `session` job needs one credential per peer. A refresh token
# cannot serve: Hydra rotates it on every use, so a run spends the copy the developer holds. An
# access token does not rotate, lasts about an hour, and is exactly what the lobby validates, so it
# is what CI gets, minted right before a dispatch.
#
# Usage:
#   scripts/ci/mint-access-token.sh <refresh-token-file> <secret-name> [--dry-run]
#
#   scripts/ci/mint-access-token.sh .secrets/refresh_token_c.txt FAF_CI_ACCESS_TOKEN_C
#   scripts/ci/mint-access-token.sh .secrets/refresh_token_c.txt FAF_CI_ACCESS_TOKEN_C --dry-run
#
# --dry-run does everything except set the secret, so the token's subject, scopes and expiry can be
# checked before a dispatch depends on them. It still spends one rotation, because Hydra rotates on
# every exchange; the rotated token is written back to the same file, so nothing is lost.
#
# The refresh token is read from the file and passed to curl on stdin, never as an argument, so it
# stays out of the process table and out of any shell history. Neither token is ever printed: the
# output is the access token's `sub`, `scp` and `exp` claims, which are not secret, and the secret
# itself goes to `gh secret set` on stdin.
#
# The rotated refresh token is written back BEFORE the secret is set. Hydra has already invalidated
# the old one by then, so losing the new one costs a manual re-bootstrap (harness-runbook.md §3).
set -euo pipefail

TOKEN_URL=${FAF_OAUTH_TOKEN_URL:-https://hydra.faforever.xyz/oauth2/token}
# The seeded "FAF Classic Client (Python)" public client, as used everywhere else in this repo.
CLIENT_ID=${FAF_OAUTH_CLIENT_ID:-95ecec08-29c1-4c48-ae0a-b000ff349cb8}

usage() {
    echo "usage: $0 <refresh-token-file> <secret-name> [--dry-run]" >&2
    exit 2
}

[ $# -ge 2 ] && [ $# -le 3 ] || usage
token_file=$1
secret_name=$2
dry_run=false
if [ $# -eq 3 ]; then
    [ "$3" = "--dry-run" ] || usage
    dry_run=true
fi

for tool in curl jq; do
    command -v "$tool" >/dev/null || { echo "$0: $tool is required" >&2; exit 1; }
done
if [ "$dry_run" = false ]; then
    command -v gh >/dev/null || { echo "$0: gh is required unless --dry-run" >&2; exit 1; }
fi
[ -r "$token_file" ] || { echo "$0: cannot read $token_file" >&2; exit 1; }

# $(<file) strips trailing newlines, which a hand-bootstrapped file often has and which Hydra would
# otherwise receive as part of the token value.
refresh_token=$(<"$token_file")
[ -n "$refresh_token" ] || { echo "$0: $token_file is empty" >&2; exit 1; }

# The token goes in on stdin (@-); every other field is a literal. The response is captured whole
# and never echoed. -w appends the status code on its own last line.
response=$(printf '%s' "$refresh_token" | curl -sS --max-time 30 -w '\n%{http_code}' \
    -X POST "$TOKEN_URL" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=refresh_token' \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode 'refresh_token@-')
http_code=${response##*$'\n'}
body=${response%$'\n'*}

if [ "$http_code" != "200" ]; then
    # Hydra's error body names the error and describes it; it never echoes the token back.
    echo "$0: token endpoint returned HTTP $http_code" >&2
    printf '%s' "$body" | jq -r '"  " + (.error // "unknown") + ": " + (.error_description // "")' >&2 \
        || echo "  (unparseable response body)" >&2
    echo "  a spent or expired refresh token needs a fresh bootstrap: see harness-runbook.md section 3" >&2
    exit 1
fi

rotated=$(printf '%s' "$body" | jq -r '.refresh_token // empty')
access_token=$(printf '%s' "$body" | jq -r '.access_token // empty')
[ -n "$access_token" ] || { echo "$0: response carried no access_token" >&2; exit 1; }

# Write the rotation back first, atomically and owner-only, to the file we were given rather than a
# copy. Hydra has already invalidated the old value, so this is the only surviving credential.
if [ -n "$rotated" ]; then
    tmp="$token_file.tmp.$$"
    ( umask 077; printf '%s' "$rotated" > "$tmp" )
    mv -f "$tmp" "$token_file"
    echo "rotated refresh token written back to $token_file"
else
    echo "warning: no rotated refresh token in the response; $token_file is unchanged" >&2
fi

# A JWT payload is base64url with the padding stripped; decode it for the operator-visible claims.
# None of sub, scp or exp is a secret, and the signature is never touched.
payload=$(printf '%s' "$access_token" | cut -d. -f2 | tr '_-' '/+')
case $(( ${#payload} % 4 )) in
    2) payload="$payload==" ;;
    3) payload="$payload=" ;;
esac
claims=$(printf '%s' "$payload" | base64 -d 2>/dev/null || true)
if [ -n "$claims" ]; then
    sub=$(printf '%s' "$claims" | jq -r '.sub // "?"')
    scp=$(printf '%s' "$claims" | jq -r 'if (.scp|type) == "array" then (.scp|join(" ")) else (.scp // .scope // "?") end')
    exp=$(printf '%s' "$claims" | jq -r '.exp // 0')
    echo "access token: sub=$sub scp=$scp"
    if [ "$exp" -gt 0 ]; then
        echo "              exp=$(date -u -d "@$exp" +%Y-%m-%dT%H:%M:%SZ) ($(( (exp - $(date +%s)) / 60 )) minutes from now)"
    fi
    case " $scp " in
        *" lobby "*) ;;
        *) echo "warning: the token has no 'lobby' scope; the lobby will reject it" >&2 ;;
    esac
else
    echo "access token minted, but its payload did not decode as a JWT (opaque token?)" >&2
fi

if [ "$dry_run" = true ]; then
    echo "dry run: secret $secret_name not set"
    exit 0
fi

printf '%s' "$access_token" | gh secret set "$secret_name"
echo "secret $secret_name set; it expires with the token above, so dispatch the workflow now"
