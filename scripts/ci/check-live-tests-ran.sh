#!/usr/bin/env bash
#
# Fail unless every named live test class ran at least one test and skipped none (WBS-2.3.3.4,
# #451).
#
# Gradle fails a test task only when its whole --tests filter matched nothing, so a renamed or moved
# class leaves its pattern matching nothing while the others still run, and the job passes having
# run less than it says. FAF_LIVE_REQUIRED cannot catch that: the test it would have failed in never
# existed. This reads the JUnit XML Gradle wrote instead. The live-tests jobs in ci.yml, release.yml
# and live-integration.yml each call it with the classes they ran.
#
# Usage:
#   scripts/ci/check-live-tests-ran.sh <module>:<fully.qualified.ClassName> ...
#
#   scripts/ci/check-live-tests-ran.sh \
#       mock-client:com.faforever.testharness.client.cli.IceSmokeLiveTest \
#       mock-game:com.faforever.testharness.game.gpgnet.GpgNetConnectionLiveSmokeTest
#
# Run it from the repository root after the integrationTest tasks, locally as well as in CI. It
# checks every class before exiting, so one run names every class that is missing or skipped.
# Problems are printed as GitHub `::error::` annotations, which read as plain lines outside Actions.
set -euo pipefail

usage() {
    echo "usage: $0 <module>:<fully.qualified.ClassName> ..." >&2
    exit 2
}

[ $# -ge 1 ] || usage

status=0
for entry in "$@"; do
    module=${entry%%:*}
    class=${entry#*:}
    [ "$module" != "$entry" ] && [ -n "$module" ] && [ -n "$class" ] || usage
    xml="$module/build/test-results/integrationTest/TEST-$class.xml"
    if [ ! -f "$xml" ]; then
        echo "::error::$class did not run: no $xml (did the class move or get renamed?)"
        status=1
        continue
    fi
    # `|| true` on each: under pipefail a grep that matches nothing fails the assignment and would
    # end the script before the message below names the class. The `${var:-0}` defaults then turn
    # "no match" into a reported failure.
    suite=$(grep -o '<testsuite [^>]*>' "$xml" | head -1 || true)
    tests=$(printf '%s' "$suite" | grep -o 'tests="[0-9]*"' | grep -o '[0-9]*' || true)
    skipped=$(printf '%s' "$suite" | grep -o 'skipped="[0-9]*"' | grep -o '[0-9]*' || true)
    if [ "${tests:-0}" -lt 1 ] || [ "${skipped:-0}" -ne 0 ]; then
        echo "::error::$class ran ${tests:-0} test(s) with ${skipped:-0} skipped; expected at least one run and none skipped"
        status=1
        continue
    fi
    echo "$class: $tests test(s), none skipped"
done
exit "$status"
