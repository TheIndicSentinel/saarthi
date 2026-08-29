#!/usr/bin/env bash
# Idempotent Cloud Agent setup for the Saarthi Android project.
# Installs the Android SDK packages the Gradle build needs (compileSdk 36,
# build-tools 35.0.0, platform-tools), points the build at it via
# local.properties, and warms the Gradle/dependency caches with a debug build.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;35.0.0"

echo "==> Android SDK home: $ANDROID_HOME"
mkdir -p "$ANDROID_HOME"

# 1. Install command-line tools if the sdkmanager isn't present yet.
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "==> Downloading Android command-line tools"
  tmp_zip="$(mktemp --suffix=.zip)"
  curl -fsSL -o "$tmp_zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
  unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f "$tmp_zip"
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2. Accept licenses (idempotent) and install the required packages.
echo "==> Accepting SDK licenses"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "==> Installing SDK packages"
sdkmanager "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null

# 3. Point the Gradle build at the SDK (local.properties is git-ignored).
echo "==> Writing local.properties"
echo "sdk.dir=$ANDROID_HOME" > "$(dirname "$0")/../local.properties"

# 4. Warm the Gradle wrapper + dependency caches with a debug build so fresh
#    agents get fast incremental builds. Non-daemon keeps it snapshot-friendly.
echo "==> Warming Gradle caches (assembleDebug)"
cd "$(dirname "$0")/.."
./gradlew --no-daemon assembleDebug

echo "==> Saarthi environment ready."
