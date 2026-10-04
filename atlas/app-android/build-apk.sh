#!/usr/bin/env bash
# Atlas Android 端构建：产出 debug APK。
# 用法：./build-apk.sh [额外 gradle 参数…]（如 --refresh-dependencies）
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 依赖构建 JDK 与桌面端一致（~/jdk/jdk-17）；依赖仓库走阿里云直连镜像，
# 本机代理失效不影响（gradle.properties 已显式绕过代理）。
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/jdk/jdk-17}"
[[ -x "$JAVA_HOME/bin/java" ]] || { echo "缺构建 JDK：$JAVA_HOME" >&2; exit 1; }
GRADLE="${GRADLE:-$HOME/tools/gradle-8.14.3/bin/gradle}"
[[ -x "$GRADLE" ]] || { echo "缺 gradle：$GRADLE" >&2; exit 1; }

JAVA_HOME="$JAVA_HOME" "$GRADLE" :app:assembleDebug --console=plain "$@"

APK="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo "APK → $APK"
# 有在线设备时可加装：adb install -r -t "$APK"
