#!/usr/bin/env bash
# [install] Incremental debug build + install to the test stand.
# Skips full rebuilds — gradle handles incrementality; only changed
# modules recompile. NO release builds here (release = CI only).
# Usage: scripts/install_inc.sh [adb-serial]
set -euo pipefail
source "$(dirname "$0")/env.sh"
ensure_toolchain

SERIAL="${1:-$ADB_TARGET}"
case "$SERIAL" in
    *:*) adb connect "$SERIAL" >/dev/null 2>&1 || true ;;
esac

cd "$ANDROID_DIR"
./gradlew -q assembleDebug 2>&1 | tail -6
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -2
adb -s "$SERIAL" shell am force-stop com.openminis.app.clone || true
adb -s "$SERIAL" shell am start -n com.openminis.app.clone/com.openminis.app.MainActivityIconAuto >/dev/null
echo "[install] done on $SERIAL"
