#!/usr/bin/env bash
# [setup] Idempotent userspace toolchain installer: JDK 17 + Android SDK
# (cmdline-tools, platform-tools, compile platform, build-tools).
# No sudo required — everything under $HOME.
set -euo pipefail
source "$(dirname "$0")/env.sh"

mkdir -p "$JAVA_HOME" "$ANDROID_HOME"

# ---------- JDK 17 (Temurin) ----------
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "[setup] downloading JDK 17..."
    curl -sL --max-time 900 -o /tmp/jdk17.tgz \
        "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    tar -C "$JAVA_HOME" --strip-components=1 -xzf /tmp/jdk17.tgz
    rm -f /tmp/jdk17.tgz
fi
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# ---------- Android cmdline-tools ----------
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "[setup] downloading Android cmdline-tools..."
    curl -sL --max-time 600 -o /tmp/cmdtools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q -o /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f /tmp/cmdtools.zip
fi

# ---------- SDK packages (compileSdk=36 per app/build.gradle.kts) ----------
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null

echo "[setup] OK: java=$($JAVA_HOME/bin/java -version 2>&1 | head -1)"
ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools" 2>/dev/null
