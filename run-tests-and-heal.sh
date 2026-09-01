#!/usr/bin/env bash
# `mvn -pl ai-healer exec:java -Dexec.mainClass=com.ai.healer.TestRunAndHeal` is now the primary
# supported way to do this (same behavior, one process instead of two); this script is kept as a
# documented fallback.
#
# Runs the Playwright/Cucumber test suite, and if it fails, automatically follows with
# HealOrchestrator against the report that run just produced - no separate manual invocation
# needed. Safe to run repeatedly: if the tests pass, or if there's nothing to heal, it says so
# and exits cleanly (exit 0) either way.
#
# Usage: ./run-tests-and-heal.sh
set -euo pipefail
cd "$(dirname "$0")"

# Captured before running the tests, in whole seconds (portable across BSD/macOS and GNU date -
# GNU's %N nanosecond extension isn't available on macOS's built-in date). Passed to
# HealOrchestrator as healer.minReportTimestamp so it refuses to process a report left over from
# an earlier, unrelated run instead of this one.
test_start_ms=$(( $(date +%s) * 1000 ))

echo "=== Running playwright-tests ==="
if mvn -pl playwright-tests test; then
    echo "=== All tests passed - nothing to heal ==="
    exit 0
fi

echo "=== Test run failed - invoking HealOrchestrator on the report just produced ==="
mvn -pl ai-healer exec:java -Dexec.mainClass=com.ai.healer.HealOrchestrator \
    -Dhealer.minReportTimestamp="${test_start_ms}"
