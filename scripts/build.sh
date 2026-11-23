#!/usr/bin/env bash
# 一键构建 debug APK（需要 JDK 17+ 与 Android SDK 35）
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f local.properties ]; then
  echo "sdk.dir=${ANDROID_HOME:-$HOME/Android/Sdk}" > local.properties
  echo "已生成 local.properties"
fi

./gradlew :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
