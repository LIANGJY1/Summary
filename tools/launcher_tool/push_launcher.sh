#!/bin/bash
set -e

export PATH="/home/liang/Android/Sdk/platform-tools:$PATH"

if [ -n "$1" ]; then
    APK_PATH="$1"
else
    APK_PATH="$(dirname "$0")/NsrLauncher.apk"
fi

DEST="/system_ext/priv-app/NsrLauncher"

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

echo "==> adb root"
adb root
sleep 0.2
adb wait-for-device

echo "==> adb remount"
adb remount
sleep 0.2

echo "==> pushing APK"
adb push "$APK_PATH" "$DEST"

echo "Done. Rebooting..."
adb reboot
