#!/usr/bin/env bash
# #508 macOS proof, on a scratch branch that is never merged. Runs the reaper tests on this OS, then
# kills `mock-client run` in HOSTING and in PLAYING, three times each, on main's jars and on the
# branch's, and records how long the adapter and mock-game outlive it (the AC allows 10 s).
#
# By hand on any Mac, from the repository root:   scripts/scratch/508/run.sh
# On Linux it hides setpriv from the client, so the reaper is what gets tested there too.
# Needs JDK 21 or later as `java` on PATH, python3, and network access (Gradle and the pinned
# adapter). Evidence lands in ./scratch-508/; summary.txt is the part to send back.
# Written for the bash 3.2 that macOS ships.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
out="$root/scratch-508"
rm -rf "$out"
mkdir -p "$out/rig" "$out/base" "$out/branch" "$out/base-src"
cd "$root"
cp scripts/scratch/508/fake_lobby.py scripts/scratch/508/drive_kill_posix.py "$out/rig/"

java_bin="$(command -v java)"
base="${BASE:-$(git merge-base HEAD origin/main)}"
{
    echo "host: $(uname -srm)"
    echo "java: $(java -version 2>&1 | head -n 1)"
    echo "branch: $(git rev-parse --short HEAD), base (main): $(git rev-parse --short "$base")"
} | tee "$out/summary.txt"

# 1. The new tests on this OS. The setpriv and SIGTERM cases skip off Linux.
tests_ok=1
if ! ./gradlew --no-daemon :shared:cleanTest :shared:test \
        --tests '*OrphanReaperTest' --tests '*SubprocessManagerShutdownTest' \
        > "$out/tests.log" 2>&1; then
    tests_ok=0
fi
python3 - "$root/shared/build/test-results/test" <<'EOF' | tee -a "$out/summary.txt"
import glob, re, sys
for path in sorted(glob.glob(sys.argv[1] + "/*.xml")):
    text = open(path, encoding="utf-8", errors="replace").read()
    for case in re.finditer(r'<testcase name="([^"]+)" classname="[^"]*\.(\w+)" time="([^"]+)"(/>|>(.*?)</testcase>)', text, re.S):
        body = case.group(5) or ""
        status = "SKIPPED" if "<skipped" in body else "FAILED" if ("<failure" in body or "<error" in body) else "passed"
        print(f"test {case.group(2)}.{case.group(1)}: {status} ({case.group(3)} s)")
EOF

# 2. The pinned adapter, then both sides' jars. main's come from a git archive of the base commit.
./gradlew --no-daemon -q downloadIceAdapter :mock-client:shadowJar :mock-game:shadowJar
cp mock-client/build/libs/mock-client-*-all.jar "$out/branch/mock-client-all.jar"
cp mock-game/build/libs/mock-game-*-all.jar "$out/branch/mock-game-all.jar"
git archive "$base" | tar -x -C "$out/base-src"
(cd "$out/base-src" && ./gradlew --no-daemon -q :mock-client:shadowJar :mock-game:shadowJar)
cp "$out"/base-src/mock-client/build/libs/mock-client-*-all.jar "$out/base/mock-client-all.jar"
cp "$out"/base-src/mock-game/build/libs/mock-game-*-all.jar "$out/base/mock-game-all.jar"

# 3. The kill runs. main is expected to fail them; the branch must pass all six.
hide=""
if [ "$(uname -s)" = "Linux" ]; then
    hide="--hide-setpriv"
fi
for side in base branch; do
    for state in hosting playing; do
        for i in 1 2 3; do
            python3 "$out/rig/drive_kill_posix.py" "$java_bin" "$out/$side/mock-client-all.jar" \
                "$out/$side/mock-game-all.jar" "$root/faf-ice-adapter.jar" "$state" "$side$i" \
                $hide 2>&1 | tee -a "$out/summary.txt" || true
        done
    done
done

passed="$(grep -c '^branch[0-9]-[a-z]*: PASS' "$out/summary.txt" || true)"
echo "branch kill runs passed: $passed of 6; tests ok: $tests_ok" | tee -a "$out/summary.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    { echo '```'; cat "$out/summary.txt"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$passed" -eq 6 ] && [ "$tests_ok" -eq 1 ]
