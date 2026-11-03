#!/usr/bin/env bash
# [test] Run JVM unit tests (Robolectric-compatible) — no device, no APK.
# Usage: scripts/test_all.sh [gradle-test-filter]
#   e.g. scripts/test_all.sh "com.openminis.app.chat.*"
set -euo pipefail
source "$(dirname "$0")/env.sh"
ensure_toolchain

cd "$ANDROID_DIR"
FILTER="${1:-}"
if [ -n "$FILTER" ]; then
    ./gradlew --offline -q testDebugUnitTest --tests "$FILTER" 2>&1 | tail -40
else
    ./gradlew --offline -q testDebugUnitTest 2>&1 | tail -40
fi
