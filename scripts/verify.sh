#!/usr/bin/env bash
# 交付前校验：编译 + 单测 + lint + APK 体检，任一环节失败都以非 0 退出。
#
# 用法：
#   scripts/verify.sh              # 校验当前 versionName 对应的 dist/*.apk
#   scripts/verify.sh 1.0.9        # 指定版本号
#
# 为什么要有这个脚本：AGENTS.md 里写的「交付前三件套全绿」以前靠自觉，
# 漏跑一次 lint 或者忘了数测试就会把半成品推出去。这里把它变成一条命令。
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=/opt/gradle/gradle-8.9/bin/gradle
[ -x "$GRADLE" ] || GRADLE=./gradlew

if [ ! -f local.properties ]; then
  echo "缺少 local.properties，写入 sdk.dir=/opt/android-sdk"
  echo "sdk.dir=/opt/android-sdk" > local.properties
fi

VERSION=${1:-$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)}
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
APK=dist/CloudBuddy-${VERSION}-debug.apk

echo "==> 版本 $VERSION (versionCode $CODE)"

echo "==> 编译 / 单测 / lint"
"$GRADLE" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline -q

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
BT=$(ls -d /opt/android-sdk/build-tools/* 2>/dev/null | tail -1)
[ -n "$BT" ] || { echo "找不到 build-tools"; exit 1; }
cp app/build/outputs/apk/debug/app-debug.apk "$APK"

# 不要写成 `aapt2 ... | head -1`：head 提前关闭管道会让 aapt2 收到 SIGPIPE，
# 配上 pipefail 就是 141 退出码，脚本会在这里莫名失败。
BADGING_LINES=$("$BT"/aapt2 dump badging "$APK")
BADGING=${BADGING_LINES%%$'\n'*}
echo "    $BADGING"
echo "$BADGING" | grep -q "versionCode='$CODE'" || { echo "versionCode 与 build.gradle.kts 不一致"; exit 1; }
echo "$BADGING" | grep -q "versionName='$VERSION'" || { echo "versionName 与 build.gradle.kts 不一致"; exit 1; }

"$BT"/apksigner verify "$APK" || { echo "签名校验失败"; exit 1; }
echo "    签名 OK"

echo "    路径 $APK"
echo "    大小 $(stat -c%s "$APK") 字节"
echo "    sha256 $(sha256sum "$APK" | cut -d' ' -f1)"
echo
echo "全部通过。把这个 sha256 抄进 README 和 docs/FIXLOG.md 的当前章节。"
