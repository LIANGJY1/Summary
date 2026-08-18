#!/bin/bash
set -e

PACKAGE="com.yadea.launcher"

echo "==> adb root"
adb root

echo "==> finding $PACKAGE PID"
PID=$(adb shell ps -A | grep "$PACKAGE" | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "Error: $PACKAGE not running"
    exit 1
fi

echo "==> killing $PACKAGE (PID: $PID)"
adb shell kill "$PID"
echo "Done."
