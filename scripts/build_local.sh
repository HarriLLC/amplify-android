#!/usr/bin/env bash
#
# Run a local build of amplify-android with full logging details.
#
# Usage:
#   ./scripts/build_local.sh                # clean build, all modules
#   ./scripts/build_local.sh :aws-auth-cognito:build
#   MODE=publish ./scripts/build_local.sh   # publishToMavenLocal instead of build
#
# Output:
#   build-logs/build-<timestamp>.log        full Gradle --info --stacktrace output
#   build-logs/build-<timestamp>.debug.log  --debug output (only if DEBUG=1)

set -euo pipefail

cd "$(dirname "$0")/.."

LOG_DIR="build-logs"
mkdir -p "$LOG_DIR"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/build-$TIMESTAMP.log"

MODE="${MODE:-build}"
DEBUG="${DEBUG:-0}"

case "$MODE" in
  build)   DEFAULT_TASKS=("clean" "build") ;;
  publish) DEFAULT_TASKS=("clean" "publishToMavenLocal") ;;
  *)       DEFAULT_TASKS=("$MODE") ;;
esac

if [[ $# -gt 0 ]]; then
  TASKS=("$@")
else
  TASKS=("${DEFAULT_TASKS[@]}")
fi

GRADLE_FLAGS=(
  --info
  --stacktrace
  --warning-mode=all
  --console=plain
)

echo "==> amplify-android local build"
echo "    tasks:   ${TASKS[*]}"
echo "    flags:   ${GRADLE_FLAGS[*]}"
echo "    log:     $LOG_FILE"
echo "    started: $(date)"
echo

{
  echo "# amplify-android build log"
  echo "# date:    $(date)"
  echo "# branch:  $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo n/a)"
  echo "# commit:  $(git rev-parse HEAD 2>/dev/null || echo n/a)"
  echo "# tasks:   ${TASKS[*]}"
  echo "# java:    $(java -version 2>&1 | head -1)"
  echo "# gradle:  $(./gradlew --version | grep -E '^Gradle ' || true)"
  echo
} > "$LOG_FILE"

set +e
./gradlew "${GRADLE_FLAGS[@]}" "${TASKS[@]}" 2>&1 | tee -a "$LOG_FILE"
STATUS=${PIPESTATUS[0]}
set -e

if [[ "$DEBUG" == "1" ]]; then
  DEBUG_LOG="$LOG_DIR/build-$TIMESTAMP.debug.log"
  echo
  echo "==> re-running with --debug -> $DEBUG_LOG"
  ./gradlew --debug "${TASKS[@]}" > "$DEBUG_LOG" 2>&1 || true
fi

echo
echo "==> finished: $(date)"
echo "    status:   $STATUS"
echo "    log:      $LOG_FILE"
exit "$STATUS"
