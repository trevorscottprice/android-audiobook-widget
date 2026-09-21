#!/usr/bin/env bash
# Builds the debug APK and installs it on the connected device.
#   ./build-and-install.sh
#
# Point JAVA_HOME and ANDROID_HOME at your own JDK 17 and Android SDK, or let
# them fall back to whatever is already on PATH.

set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${ANDROID_HOME:-}" ]; then
    PATH="$ANDROID_HOME/platform-tools:$PATH"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH

# Prefer the wrapper: it pins the Gradle version the project was built against.
if [ -x ./gradlew ]; then
    gradle_cmd=./gradlew
elif command -v gradle >/dev/null 2>&1; then
    gradle_cmd=gradle
else
    echo "No Gradle wrapper and no gradle on PATH." >&2
    exit 1
fi

echo "Building..."
"$gradle_cmd" assembleDebug --console=plain

apk=app/build/outputs/apk/debug/app-debug.apk

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found. Set ANDROID_HOME or add platform-tools to PATH." >&2
    echo "APK is ready at $apk"
    exit 1
fi

if ! adb devices | awk 'NR > 1 && $2 == "device"' | grep -q .; then
    cat <<EOF

No device detected. On the phone:
  Settings > About phone > tap Build number 7x
  Settings > Developer options > USB debugging = on
  Replug the cable and accept the "Allow USB debugging?" prompt

Samsung devices: turn off Auto Blocker first, or USB debugging
stays greyed out (Settings > Security and privacy > Auto Blocker).

APK is ready at $apk
EOF
    exit 1
fi

echo "Installing..."
adb install -r "$apk"

# Open the setup screen so notification access can be granted.
adb shell am start -n com.trevorprice.audiobookwidget/.MainActivity >/dev/null
echo "Installed. The setup screen should be open on your phone."
