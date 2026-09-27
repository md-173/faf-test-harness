#!/usr/bin/env bash
#
# Run one session through the live lobby from this checkout's release jars, as every session in
# live-integration.yml's session job runs (WBS-2.3.3.3, #431). The passing run and the runs that
# follow it share this one command, so what the later runs prove is about the same invocation.
#
# Usage:
#   scripts/ci/run-session.sh <work-dir> [session option...]
#
#   scripts/ci/run-session.sh "$WORK"
#   scripts/ci/run-session.sh "$GITHUB_WORKSPACE/session-drop" --mock-game-udp-drop-percent=100
#
# <work-dir> becomes the session's working directory, so its logs land in <work-dir>/logs/. It must
# be new or empty: Logback appends to a log it finds, and a step reading that log would take an
# earlier run's lines for this one's. The options after it follow the per-peer credentials, where
# `session` still accepts the root options it inherits, so a fault flag there reaches every peer.
#
# Reads PEERS, TOKENS (the directory holding c.txt and d.txt, one pre-signed access token per peer)
# and FAF_UID_BINARY from the job's environment, and takes the adapter jar downloadIceAdapter wrote
# and the two shadow jars from GITHUB_WORKSPACE. Exits with the session's own code: 0 when it
# passed, 70 when a checkpoint failed or a process outlived teardown, 2 for a bad invocation. It
# also exits 2, before anything starts, when the work directory already holds files, the
# environment is incomplete, or a jar is missing or ambiguous.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <work-dir> [session option...]" >&2
    exit 2
fi
work=$1
shift

missing=""
for name in PEERS TOKENS FAF_UID_BINARY GITHUB_WORKSPACE; do
    [ -n "${!name:-}" ] || missing="$missing $name"
done
if [ -n "$missing" ]; then
    echo "::error::$0 needs these set in the environment:$missing"
    exit 2
fi

# On a runner every directory is new. A second run by hand in a used one would otherwise pass or
# fail on the lines of the run before it.
if [ -e "$work" ] && [ -n "$(ls -A "$work")" ]; then
    echo "::error::$work already holds files, and a session appends to any log it finds there, so its checks could read an earlier run's lines; give each session a new directory"
    exit 2
fi

# nullglob matters: without it a pattern that matches nothing stays as the literal pattern, which
# the count check would take for one jar, and java would fail later with an unexplained "unable to
# access jarfile <pattern>".
shopt -s nullglob
jars=("$GITHUB_WORKSPACE"/mock-client/build/libs/mock-client-*-all.jar)
if [ "${#jars[@]}" -ne 1 ]; then
    echo "::error::expected one mock-client shadow jar, found ${#jars[@]}"
    exit 2
fi
client=${jars[0]}
jars=("$GITHUB_WORKSPACE"/mock-game/build/libs/mock-game-*-all.jar)
if [ "${#jars[@]}" -ne 1 ]; then
    echo "::error::expected one mock-game shadow jar, found ${#jars[@]}"
    exit 2
fi
game=${jars[0]}
shopt -u nullglob

# One variable for both peers, because they have to agree: `session` refuses a run that mixes the
# two credential channels, so naming the flag once makes that structural.
CREDENTIAL_FLAG=--peer-access-token-file

umask 077
mkdir -p "$work"
cd "$work"
exec java -jar "$client" \
    --lobby-websocket-url=wss://ws.faforever.xyz \
    --unique-id=00000000-0000-0000-0000-000000000000 \
    --uid-binary-path="$FAF_UID_BINARY" \
    --ice-adapter-binary-path="$GITHUB_WORKSPACE/faf-ice-adapter.jar" \
    --mock-game-binary-path="$game" \
    session \
        --peers="$PEERS" \
        "$CREDENTIAL_FLAG=$TOKENS/c.txt" \
        "$CREDENTIAL_FLAG=$TOKENS/d.txt" \
        "$@"
