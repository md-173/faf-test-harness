#!/usr/bin/env bash
#
# Run one session from this checkout's release jars, as every session in live-integration.yml's
# session job (WBS-2.3.3.3, #431) and local-stack.yml's job (WBS-2.3.3.2, #413) runs. The passing
# run and the runs that follow it share this one command, so what the later runs prove is about the
# same invocation, and the two jobs differ only in what they set in the environment.
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
# Reads from the job's environment: PEERS; TOKENS, a directory holding one pre-signed access token
# per peer as <name>.txt; LOBBY_URL; and one unique_id source, FAF_UID_BINARY or UNIQUE_ID. Takes
# the adapter jar downloadIceAdapter wrote and the two shadow jars from GITHUB_WORKSPACE. Exits with
# the session's own code: 0 when it passed, 70 when a checkpoint failed or a process outlived
# teardown, 2 for a bad invocation. It also exits 2, before anything starts, when the work directory
# already holds files, the environment is incomplete, the token files do not match PEERS, or a jar
# is missing or ambiguous.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <work-dir> [session option...]" >&2
    exit 2
fi
work=$1
shift

missing=""
for name in PEERS TOKENS GITHUB_WORKSPACE; do
    [ -n "${!name:-}" ] || missing="$missing $name"
done
if [ -n "$missing" ]; then
    echo "::error::$0 needs these set in the environment:$missing"
    exit 2
fi

# The lobby's unique_id: faf-uid's output where the lobby's policy check reads it (FAF_UID_BINARY,
# which live-integration.yml provisions for FAF's test lobby), or a fixed UNIQUE_ID where that check
# is off (local-stack.yml's local lobby). The client refuses to start without one of them.
if [ -n "${FAF_UID_BINARY:-}" ]; then
    uid=(--uid-binary-path="$FAF_UID_BINARY")
elif [ -n "${UNIQUE_ID:-}" ]; then
    uid=(--unique-id="$UNIQUE_ID")
else
    echo "::error::$0 needs FAF_UID_BINARY, or UNIQUE_ID for a lobby whose policy check is off"
    exit 2
fi

# The lobby is the caller's: live-integration.yml sets FAF's test lobby, and local-stack.yml its
# local one. This script names none of its own, so without LOBBY_URL the client's own setting
# decides.
lobby=()
if [ -n "${LOBBY_URL:-}" ]; then
    lobby=(--lobby-websocket-url="$LOBBY_URL")
fi

# On a runner every directory is new. A second run by hand in a used one would otherwise pass or
# fail on the lines of the run before it.
if [ -e "$work" ] && [ -n "$(ls -A "$work")" ]; then
    echo "::error::$work already holds files, and a session appends to any log it finds there, so its checks could read an earlier run's lines; give each session a new directory"
    exit 2
fi

if [ ! -d "$TOKENS" ]; then
    echo "::error::TOKENS is $TOKENS, which is not a directory"
    exit 2
fi
# Absolute, because the session runs from the work directory. CDPATH is cleared so that cd cannot
# print a directory into the result.
token_dir=$(CDPATH='' cd -- "$TOKENS" && pwd)

# One variable for every peer, because they have to agree: `session` refuses a run that mixes the
# two credential channels, so naming the flag once makes that structural.
CREDENTIAL_FLAG=--peer-access-token-file

# nullglob matters: without it a pattern that matches nothing stays as the literal pattern, which
# the count checks would take for one file, and java would fail later with an unexplained "unable to
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
# One token file per peer, in name order, so the first file's account hosts. The count has to match
# PEERS: `session` refuses fewer files than peers, but quietly uses the first PEERS of a longer list.
credentials=()
for file in "$token_dir"/*.txt; do
    if [ -f "$file" ]; then
        credentials+=("$CREDENTIAL_FLAG=$file")
    fi
done
shopt -u nullglob
if [ "${#credentials[@]}" -ne "$PEERS" ]; then
    echo "::error::$TOKENS holds ${#credentials[@]} token files and PEERS is $PEERS; give each peer exactly one"
    exit 2
fi

umask 077
mkdir -p "$work"
cd "$work"
exec java -jar "$client" \
    "${lobby[@]}" \
    "${uid[@]}" \
    --ice-adapter-binary-path="$GITHUB_WORKSPACE/faf-ice-adapter.jar" \
    --mock-game-binary-path="$game" \
    session \
        --peers="$PEERS" \
        "${credentials[@]}" \
        "$@"
