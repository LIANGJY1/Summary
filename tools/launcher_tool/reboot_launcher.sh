#!/bin/bash
set -e

export PATH="/home/liang/Android/Sdk/platform-tools:$PATH"

PACKAGE="com.yadea.launcher"

echo "==> adb root"
adb root
sleep 1

echo "==> finding $PACKAGE PID"
PID=$(adb shell ps -A | grep "$PACKAGE" | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "Error: $PACKAGE not running"
    exit 1
fi

echo "==> killing $PACKAGE (PID: $PID)"
adb shell kill "$PID"
echo "Done."
