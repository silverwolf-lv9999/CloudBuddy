#!/usr/bin/env bash
# 交付前校验：编译 + 单测 + lint + APK 体检 + 签名比对，任一环节失败都以非 0 退出。
#
# 用法：
#   scripts/verify.sh                # 构建并校验正式包（release），归档到 dist/
#   scripts/verify.sh --debug        # 只出 debug 包时用
#   scripts/verify.sh 1.1.0          # 指定版本号（默认读 build.gradle.kts）
#
# 为什么要有这个脚本：AGENTS.md 里写的「交付前三件套全绿」以前靠自觉，
# 漏跑一次 lint 或者忘了数测试就会把半成品推出去。这里把它变成一条命令。
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=/opt/gradle/gradle-8.9/bin/gradle
[ -x "$GRADLE" ] || GRADLE=./gradlew

# 沙箱里依赖全在本地缓存，不带 --offline 会去连镜像然后超时；
# GitHub Actions 上正好相反，必须联网拉依赖。
OFFLINE=--offline
if [ -n "${CI:-}" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
  OFFLINE=""
fi

SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}

if [ ! -f local.properties ]; then
  echo "缺少 local.properties，写入 sdk.dir=$SDK_ROOT"
  echo "sdk.dir=$SDK_ROOT" > local.properties
fi

MODE=release
VERSION=""
for arg in "$@"; do
  case "$arg" in
    --debug) MODE=debug ;;
    *) VERSION=$arg ;;
  esac
done

VERSION=${VERSION:-$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)}
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
APK=dist/CloudBuddy-${VERSION}-${MODE}.apk

echo "==> 版本 $VERSION (versionCode $CODE)，模式 $MODE"

if [ "$MODE" = release ] && ! grep -q '^release.storeFile=' local.properties; then
  echo "local.properties 里没有 release.storeFile，正式包无法签名。"
  echo "先用 keytool 生成密钥并写入 local.properties，见 AGENTS.md「正式签名」一节。"
  exit 1
fi

echo "==> 编译 / 单测 / lint"
if [ "$MODE" = release ]; then
  "$GRADLE" :app:assembleRelease :app:testDebugUnitTest :app:lintDebug $OFFLINE -q
else
  "$GRADLE" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug $OFFLINE -q
fi

echo "==> 单测统计"
# 注意：grep 没匹配到会返回 1，配合 set -o pipefail 会直接中断脚本，
# 所以每处都包一层 `|| true`。这几个数在 xml 里缺属性时就是 0，不是错误。
count_attr() {
  (grep -h -o "$1=\"[0-9]*\"" app/build/test-results/testDebugUnitTest/*.xml || true) |
    awk -F'"' '{s+=$2} END {print s+0}'
}
TESTS=$(count_attr tests)
FAILS=$(count_attr failures)
ERRS=$(count_attr errors)
echo "    单测 $TESTS，失败 $FAILS，错误 $ERRS"
[ "$TESTS" -gt 0 ] || { echo "没有任何单测被执行，视同失败"; exit 1; }
[ "$FAILS" -eq 0 ] || exit 1
[ "$ERRS" -eq 0 ] || exit 1

echo "==> lint"
LINT=app/build/reports/lint-results-debug.xml
if [ -f "$LINT" ]; then
  LINT_ERR=$( (grep -o 'severity="Error"' "$LINT" || true) | wc -l | tr -d ' ')
  echo "    error $LINT_ERR"
  [ "$LINT_ERR" -eq 0 ] || exit 1
else
  echo "    没有找到 lint 报告，视同失败"
  exit 1
fi

echo "==> APK 体检"
BT=$(ls -d "$SDK_ROOT"/build-tools/* 2>/dev/null | tail -1)
[ -n "$BT" ] || { echo "找不到 build-tools"; exit 1; }
mkdir -p dist
if [ "$MODE" = release ]; then
  cp app/build/outputs/apk/release/app-release.apk "$APK"
else
  cp app/build/outputs/apk/debug/app-debug.apk "$APK"
fi

# 不要写成 `aapt2 ... | head -1`：head 提前关闭管道会让 aapt2 收到 SIGPIPE，
# 配上 pipefail 就是 141 退出码，脚本会在这里莫名失败。
BADGING_LINES=$("$BT"/aapt2 dump badging "$APK")
BADGING=${BADGING_LINES%%$'\n'*}
echo "    $BADGING"
echo "$BADGING" | grep -q "versionCode='$CODE'" || { echo "versionCode 与 build.gradle.kts 不一致"; exit 1; }
echo "$BADGING" | grep -q "versionName='$VERSION'" || { echo "versionName 与 build.gradle.kts 不一致"; exit 1; }

"$BT"/apksigner verify "$APK" || { echo "签名校验失败"; exit 1; }

if [ "$MODE" = release ]; then
  # 正式包必须是自己的证书。apksigner 只验「签名自洽」，用 debug key 签照样能过，
  # 所以这里额外比对 DN，防止把 debug 包当发布版传上去。
  CERTS=$("$BT"/apksigner verify --print-certs "$APK")
  if echo "$CERTS" | grep -q "CN=Android Debug"; then
    echo "这个包用的是 debug 签名，不能发布"
    exit 1
  fi
  echo "$CERTS" | grep -q "CN=CloudBuddy" || { echo "证书 DN 不是 CloudBuddy，检查 local.properties"; exit 1; }
  echo "    签名 OK（正式证书）"
  echo "$CERTS" | grep "SHA-256 digest" | head -1 | sed 's/^/    /'
else
  echo "    签名 OK（debug）"
fi

echo "    路径 $APK"
echo "    大小 $(stat -c%s "$APK") 字节"
echo "    sha256 $(sha256sum "$APK" | cut -d' ' -f1)"
echo
echo "全部通过。发 Release 时把这个 sha256 贴进说明，并把安装包作为资产上传。"
