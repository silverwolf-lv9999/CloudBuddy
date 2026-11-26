# 开发记录

> **这份笔记停留在 1.0.x**（调研结论、构建环境、早期待验证项）。后续版本的结论以
> `docs/FIXLOG.md`（改了什么、为什么）和 `docs/PERFLOG.md`（性能排查）为准；
> `docs/ARCHITECTURE.md` 头部有当前代码规模。下面的「待真机验证」多数已经过真机复核。

## 1. 调研

- 参考仓库 [Wearbuddy](https://github.com/Oride-it/Wearbuddy)（Wear OS 客户端）：`docs/workbuddy-protocol.md` 记录了官方 web 端与 Flutter APK 逆向得到的端点；`data/` 下是完整的云端会话客户端实现（`WorkBuddyCloudClient.kt`、`AcpEvents.kt`、`NativeLogin.kt`、`SandboxFiles.kt`）。
- 界面参考 [Agents Anywhere](https://github.com/anywhere-labs/Agents-Anywhere) 的 `android/`：Kotlin + Compose + Material 3，`navigation/AppDestination` 的页面划分（Login / Sessions / SessionDetail / Files / Devices / Terminal / Settings），设计系统在 `ui/designsystem`。
- 结论：WorkBuddy 的「云端」不是一层 REST 转发，而是 ①console 会话元数据 ②ACP 长连接 ③E2B 沙箱文件网关 三者组合。任何只实现 ①+② 的客户端都会丢掉工作区。

## 2. 关键实现决策

| 决策 | 原因 |
| --- | --- |
| 不 fork Agents Anywhere，重写精简版 | 它的数据层与 v2 API 强耦合（设备/终端/审批），依赖也重（sora-editor、termux terminal-view、MLKit）；移植成本高于按 WorkBuddy 协议新写 |
| 保留 `AcpConnection` 常驻 + 单流回放 | 官方前端历史与实时事件共用一条 SSE，新开连接会让实时进度与 UI 脱钩 |
| 沙箱目录列表走 E2B envd Connect RPC | 官方 Web 端 `CloudFileManager.listDirectory` 明确用 `POST /filesystem.Filesystem/ListDir` + `Connect-Protocol-Version: 1`；此前猜测的 REST 路径全部 404 |
| 附件优先写入沙箱 | 让 agent 直接读写路径，而不是喂 base64；失败才回退内联 |
| 状态判定用 stopReason/endTurn/state_update | 只看 HTTP 结束会误判；历史快照不能结束新一轮 |
| 路径收敛到 `/workspace` `/root` | 三种 URI 写法（`agent://`、`file:///workspace`、`file:///root`）需统一，并阻断 `..` 越权 |

## 3. 构建环境（本机沙箱）

- `dl.google.com` 不可达，改用腾讯云镜像装 Android SDK：
  `https://mirrors.cloud.tencent.com/AndroidSDK/` + `SDK_TEST_BASE_URL`
  （platform-tools、platforms;android-35、build-tools;35.0.0）
- Maven 依赖走阿里云镜像（`settings.gradle.kts` 已写入）。
- Gradle wrapper 直连 `services.gradle.org` 超时，构建时改用镜像下载的 `gradle-8.9`；仓库内的 wrapper 配置保持官方地址，网络正常的机器可直接 `./gradlew`。
- JDK：Zulu 20，AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35。

## 4. 待真机验证的项

> 这些点无法在无账号的构建环境里验证，界面已在相应位置给出诊断信息。

1. 沙箱是否会在长时间空闲后过期、重新进入会话后 49983 网关是否换地址（已实现 401/403/404 自动刷新会话重试）。
2. 企业账号下 `X-Enterprise-Id` / `X-Tenant-Id` 是否足以拿到完整模型列表（已回填账号 `enterpriseId` 与 `uid`）。
3. 官方是否对非官方 UA / `X-Client-Platform` 做额外校验。
4. `session/prompt` 的 `stopReason` 是否与 `session/endTurn` 同时到达（已做幂等处理）。
5. askuser 在 ACP 里以 `session/request_permission` 还是 REST `questions` 字段出现（两条路径都已实现）。

## 5. 后续可做

- 云文档（spaces / tdocs）与插件市场
- 会话搜索（`title` 参数已支持，未接 UI）
- 工作区多选、批量下载、文本编辑保存
- 前台服务保活长连接（当前依赖 Activity 生命周期）

## 6. 构建与验证结果

环境：Zulu JDK 20、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、compileSdk 35 / minSdk 26。

```bash
SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/ \
  gradle :app:assembleDebug :app:testDebugUnitTest
```

- `BUILD SUCCESSFUL`（`assembleDebug` + `testDebugUnitTest` + `lintDebug`），
  产物 `dist/CloudBuddy-1.0.1-debug.apk`（17.3 MB，debug 签名，versionCode 2）。
- 单测 26 个全部通过，lint 0 error。覆盖：
  - 沙箱 URI ↔ 路径（三种写法、百分号解码、`..`/`\`/越界拒绝）
  - 对象键的三种写法变体
  - 云端状态字符串 → 客户端状态（含嵌套 `status.state`）
  - ACP 用户回显去重、助手分片（含空白）拼接
  - `session/endTurn` 判定完成、历史快照不误判完成
  - `tool_call` 原地更新为单行、产物通知与 resource_link 收集
  - `request_permission` → WAITING 且带回 requestId / 选项
  - **1.0.1 新增**：ListDir 响应解析（type 枚举/数字/mode、ISO 与 timestamp 时间、
    多种信封、越界条目拒绝）、文件网关地址推导、username 规则、
    ACP `session/load` 的 availableModels / currentModelId 解析
- 构建期的坑：`dl.google.com` 不可达会让 AGP 的 SDK 附件索引请求挂住；用
  `SDK_TEST_BASE_URL` 指向腾讯镜像 + JVM `sun.net.client.default*Timeout` 后正常。
  国内网络下 maven 走阿里云镜像。
- 1.0.1 修复期踩到的坑：Kotlin 支持嵌套块注释，KDoc 里写 `/root/*` 会开启一个
  嵌套注释，直到代码里 `"*/*"` 字符串中的 `*/` 才闭合，导致整个文件解析错乱。

## 6.5 交付校验脚本（1.0.9 后补）

`AGENTS.md` 里写的「三件套全绿才算完成」以前靠自觉：数测试数要手敲 awk，
校验 APK 要手拼 `aapt2` 命令，漏一步也没人拦。新增 `scripts/verify.sh` 一条命令做完，
任一环节失败非 0 退出：

- 编译 + 单测 + lint（`--offline`）；
- 从 JUnit XML 里数 tests / failures / errors，**0 个测试也算失败**（防止过滤规则写错后空跑）；
- lint 报告必须存在且 0 error；
- `aapt2 dump badging` 的 versionCode/versionName 必须和 `build.gradle.kts` 一致；
- `apksigner verify` + 归档到 `dist/` + 打印 sha256。

写这个脚本时踩到两个 shell 坑，都是 `set -o pipefail` 放大的：

1. `grep -o 'severity="Error"' file | wc -l`——没有匹配时 grep 返回 1，管道整体失败，
   脚本在「检查通过」的情况下反而退出。改成 `( grep ... || true ) | wc -l`。
2. `aapt2 dump badging x.apk | head -1`——`head` 取完首行就关管道，`aapt2` 收到 SIGPIPE，
   退出码 141。改成先取全量输出，再用参数展开截首行（`${VAR%%$'\n'*}`）。

## 7. 没有做真机验证的部分

构建环境没有 KVM（`/dev/kvm` 不存在）也没有可用账号，**没有跑过模拟器和真机**。因此以下需要你首次上手时确认：

1. 登录链路（短信、SSO 续期）是否被官方风控拦截。
2. `ListDir` 在该账号的沙箱上是否需要额外的 `Authorization: Basic` 用户头（官方 Web 端未带，服务端默认 user）。
3. 上传/删除是否被网关允许。
4. 长连接在息屏/切后台后的存活时间（当前没有前台服务）。
