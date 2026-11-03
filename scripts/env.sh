#!/usr/bin/env bash
# [env] Userspace toolchain paths for OpenMinis local development.
# Source this file before any gradle/adb work:
#   source scripts/env.sh
# Everything lives in $HOME — no sudo, no system packages.

export JAVA_HOME="$HOME/jdk17"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/src/android"

# Waydroid adb target is the default test stand; override with ADB_TARGET.
ADB_TARGET="${ADB_TARGET:-192.168.240.112:5555}"

ensure_toolchain() {
    if [ ! -x "$JAVA_HOME/bin/java" ]; then
        echo "ERROR: JDK17 not found at $JAVA_HOME — run scripts/setup_toolchain.sh" >&2
        return 1
    fi
    if [ ! -d "$ANDROID_HOME/platforms" ]; then
        echo "ERROR: Android SDK not found at $ANDROID_HOME — run scripts/setup_toolchain.sh" >&2
        return 1
    fi
}
