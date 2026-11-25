#!/usr/bin/env bash
# 交付前校验：编译 + 单测 + lint + APK 体检 + 签名比对，任一环节失败都以非 0 退出。
#
# 用法：
#   scripts/verify.sh                # 构建并校验三个发行包（release），归档到 dist/
#   scripts/verify.sh --debug        # 只出 debug 包时用
#   scripts/verify.sh --base         # 只校验基础版
#   scripts/verify.sh --full         # 只校验完整版（与基础版同包名，可互相覆盖）
#   scripts/verify.sh --plus         # 只校验共存版（包名带 .plus）
#   scripts/verify.sh 1.2.0          # 指定版本号（默认读 build.gradle.kts）
#
# 三个发行包（见 app/build.gradle.kts 的 edition 维度）：
#   base  基础版：包名 cn.cloudbuddy.app，只有云端能力
#   full  完整版：包名同样是 cn.cloudbuddy.app，多了本机 agent / 第三方 API / 终端
#         —— 与 base 同包名，装它就是覆盖升级，登录态与设置原样保留；装回 base 就是降级
#   plus  共存版：包名 cn.cloudbuddy.app.plus，内容同 full，可与上面两个同时安装
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
EDITIONS=()
for arg in "$@"; do
  case "$arg" in
    --debug) MODE=debug ;;
    --base) EDITIONS+=(base) ;;
    --full) EDITIONS+=(full) ;;
    --plus) EDITIONS+=(plus) ;;
    *) VERSION=$arg ;;
  esac
done
# 不带参数时三个都做：只发一个包等于让一半用户拿不到更新。
[ ${#EDITIONS[@]} -gt 0 ] || EDITIONS=(base full plus)

VERSION=${VERSION:-$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)}
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)

# Gradle 任务名与产物目录都要按 edition 拼：assembleFullRelease / full/release/
cap() { echo "$(tr '[:lower:]' '[:upper:]' <<<"${1:0:1}")${1:1}"; }

# 每个包应有的 applicationId。这是"能不能覆盖安装"的唯一判据，
# 写错了用户装上去就是两个图标，所以脚本必须自己卡住，而不是靠人记得。
expected_id() {
  case "$1" in
    plus) echo "cn.cloudbuddy.app.plus" ;;
    *) echo "cn.cloudbuddy.app" ;;
  esac
}

echo "==> 版本 $VERSION (versionCode $CODE)，模式 $MODE，发行包 ${EDITIONS[*]}"

if [ "$MODE" = release ] && ! grep -q '^release.storeFile=' local.properties; then
  echo "local.properties 里没有 release.storeFile，正式包无法签名。"
  echo "先用 keytool 生成密钥并写入 local.properties，见 AGENTS.md「正式签名」一节。"
  exit 1
fi

TASKS=()
for ed in "${EDITIONS[@]}"; do
  TASKS+=(":app:assemble$(cap "$ed")${MODE^}")
  TASKS+=(":app:test$(cap "$ed")DebugUnitTest")
  TASKS+=(":app:lint$(cap "$ed")Debug")
done

echo "==> 编译 / 单测 / lint"
"$GRADLE" "${TASKS[@]}" $OFFLINE --build-cache --configuration-cache -q

echo "==> 单测统计"
# 注意：grep 没匹配到会返回 1，配合 set -o pipefail 会直接中断脚本，
# 所以每处都包一层 `|| true`。这几个数在 xml 里缺属性时就是 0，不是错误。
count_attr() {
  # 只统计本次几个变体的报告：build/ 里可能还躺着旧任务（testDebugUnitTest）的 xml，
  # 一起加会把上一次的数字也算进来。
  (
    for ed in "${EDITIONS[@]}"; do
      grep -h -o "$1=\"[0-9]*\"" "app/build/test-results/test$(cap "$ed")DebugUnitTest/"*.xml 2>/dev/null || true
    done
  ) | awk -F'"' '{s+=$2} END {print s+0}'
}
TESTS=$(count_attr tests)
FAILS=$(count_attr failures)
ERRS=$(count_attr errors)
echo "    单测 $TESTS，失败 $FAILS，错误 $ERRS"
[ "$TESTS" -gt 0 ] || { echo "没有任何单测被执行，视同失败"; exit 1; }
[ "$FAILS" -eq 0 ] || exit 1
[ "$ERRS" -eq 0 ] || exit 1

echo "==> lint"
for ed in "${EDITIONS[@]}"; do
  # 报告文件名是小写的变体名（lint-results-fullDebug.xml），任务名才是首字母大写。
  LINT=app/build/reports/lint-results-${ed}Debug.xml
  if [ -f "$LINT" ]; then
    LINT_ERR=$( (grep -o 'severity="Error"' "$LINT" || true) | wc -l | tr -d ' ')
    echo "    $ed error $LINT_ERR"
    [ "$LINT_ERR" -eq 0 ] || exit 1
  else
    echo "    没有找到 $ed 的 lint 报告，视同失败"
    exit 1
  fi
done

echo "==> APK 体检"
BT=$(ls -d "$SDK_ROOT"/build-tools/* 2>/dev/null | tail -1)
[ -n "$BT" ] || { echo "找不到 build-tools"; exit 1; }
mkdir -p dist

for ed in "${EDITIONS[@]}"; do
  SRC=app/build/outputs/apk/$ed/$MODE/app-$ed-$MODE.apk
  [ -f "$SRC" ] || { echo "找不到 $ed 的产物 $SRC"; exit 1; }
  # 文件名里保留 flavor：三个包装机顺序不同，靠文件名认出"哪个能覆盖哪个"。
  if [ "$ed" = full ]; then
    APK=dist/CloudBuddy-${VERSION}-${MODE}.apk
  else
    APK=dist/CloudBuddy-${VERSION}-${ed}-${MODE}.apk
  fi
  cp "$SRC" "$APK"

  # 不要写成 `aapt2 ... | head -1`：head 提前关闭管道会让 aapt2 收到 SIGPIPE，
  # 配上 pipefail 就是 141 退出码，脚本会在这里莫名失败。
  BADGING_LINES=$("$BT"/aapt2 dump badging "$APK")
  BADGING=${BADGING_LINES%%$'\n'*}
  echo "    [$ed] $BADGING"
  echo "$BADGING" | grep -q "versionCode='$CODE'" || { echo "$ed versionCode 与 build.gradle.kts 不一致"; exit 1; }
  # 三个包的版本名完全一致（不加后缀）：同一版本号就是同一份代码，靠包名区分档位。
  echo "$BADGING" | grep -q "versionName='$VERSION'" || { echo "$ed versionName 应为 $VERSION"; exit 1; }
  WANT_ID=$(expected_id "$ed")
  echo "$BADGING" | grep -q "package: name='$WANT_ID'" || {
    echo "$ed 的包名应为 $WANT_ID，实际不是——包名错了就没法覆盖安装"
    exit 1
  }

  "$BT"/apksigner verify "$APK" || { echo "$ed 签名校验失败"; exit 1; }

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
done

echo "全部通过。发 Release 时把三个包的 sha256 都贴进说明，三个安装包都作为资产上传。"
echo "（包名：基础版与完整版 cn.cloudbuddy.app，可互相覆盖；共存版 cn.cloudbuddy.app.plus）"
