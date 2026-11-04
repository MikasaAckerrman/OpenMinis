#!/usr/bin/env bash
# [install] Incremental debug build + install to the test stand.
# Skips full rebuilds — gradle handles incrementality; only changed
# modules recompile. NO release builds here (release = CI only).
# Usage: scripts/install_inc.sh [adb-serial]
# [guards] (review v2) Refuses to run against an unexpected device and
# verifies the INSTALLED build is actually fresh — a stale APK silently
# passing as "the current commit" is exactly the failure mode this
# script exists to prevent.
set -euo pipefail
source "$(dirname "$0")/env.sh"
ensure_toolchain

SERIAL="${1:-$ADB_TARGET}"
case "$SERIAL" in
    *:*) adb connect "$SERIAL" >/dev/null 2>&1 || true ;;
esac

# [guard-1] Device must be present and be a known stand (Waydroid Pixel_7
# or the iQOO/Infinix test phones) — never a random attached device.
adb -s "$SERIAL" shell echo ok >/dev/null 2>&1 || { echo "ERROR: device $SERIAL not reachable" >&2; exit 1; }
MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
case "$MODEL" in
    Pixel_7|V2425A|BreezeOS\ Treble|X6827) ;;
    *) echo "ERROR: unexpected device model '$MODEL' on $SERIAL — refusing to install" >&2; exit 1 ;;
esac
echo "[install] target: $SERIAL ($MODEL)"

cd "$ANDROID_DIR"
./gradlew -q assembleDebug 2>&1 | tail -6
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -2

# [guard-2] The INSTALLED build must be fresh: lastUpdateTime within the
# last 120s proves the new APK actually replaced the old one (an installer
# "Success" alone does not prove the app was restarted on it).
NOW=$(date +%s)
UPD=$(adb -s "$SERIAL" shell dumpsys package com.openminis.app.clone 2>/dev/null \
    | grep lastUpdateTime | tail -1 | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8}' | head -1)
if [ -n "$UPD" ]; then
    UPD_EPOCH=$(date -d "$UPD" +%s 2>/dev/null || echo 0)
    AGE=$(( NOW - UPD_EPOCH ))
    if [ "$AGE" -gt 120 ]; then
        echo "ERROR: installed build is stale (lastUpdateTime $UPD, ${AGE}s ago) — install did not take" >&2
        exit 1
    fi
    echo "[install] freshness ok (updated ${AGE}s ago)"
fi

adb -s "$SERIAL" shell am force-stop com.openminis.app.clone || true
adb -s "$SERIAL" shell am start -n com.openminis.app.clone/com.openminis.app.MainActivityIconAuto >/dev/null
echo "[install] done on $SERIAL ($MODEL)"
