#!/usr/bin/env bash
# 一键构建三个发行包的 debug APK（需要 JDK 17+ 与 Android SDK 35）
#
#   base  基础版：包名 cn.cloudbuddy.app，只有云端能力
#   full  完整版：包名同样是 cn.cloudbuddy.app（覆盖安装基础版即升级，数据保留）
#   plus  共存版：包名 cn.cloudbuddy.app.plus，内容同完整版，可与前两个同时安装
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f local.properties ]; then
  echo "sdk.dir=${ANDROID_HOME:-$HOME/Android/Sdk}" > local.properties
  echo "已生成 local.properties"
fi

./gradlew :app:assembleBaseDebug :app:assembleFullDebug :app:assemblePlusDebug
echo "APK(base): app/build/outputs/apk/base/debug/app-base-debug.apk          (cn.cloudbuddy.app)"
echo "APK(full): app/build/outputs/apk/full/debug/app-full-debug.apk          (cn.cloudbuddy.app)"
echo "APK(plus): app/build/outputs/apk/plus/debug/app-plus-debug.apk          (cn.cloudbuddy.app.plus)"
