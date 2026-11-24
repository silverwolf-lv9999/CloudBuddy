# AGENTS.md

给编码 agent 的项目说明书。**只写猜不到、且踩过坑的东西**——能从代码或 README 看出来的不重复。
人类贡献者看 [README.md](README.md)，架构评估看 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 这是什么

CloudBuddy：WorkBuddy 云端 Agent 的第三方 Android 客户端。Kotlin + Jetpack Compose，
单模块 `:app`，约 8 500 行，minSdk 26。

**产品前提**：对话跑在官方云端沙箱里（`cwd=/workspace`），不是本地 API 反代。
代价是协议全部要自己逆向，改动前先确认事件形状，不要凭直觉加字段。

## 命令

全部在仓库根目录执行。Gradle 不在 PATH 里，用绝对路径。

```bash
# 编译
/opt/gradle/gradle-8.9/bin/gradle :app:assembleDebug --offline

# 单测（纯 JVM，不需要设备/模拟器）
/opt/gradle/gradle-8.9/bin/gradle :app:testDebugUnitTest --offline

# lint
/opt/gradle/gradle-8.9/bin/gradle :app:lintDebug --offline

# 交付前必跑：编译 + 单测 + lint + APK 体检 + 签名比对，任一失败非 0 退出
bash scripts/verify.sh

# 需要 debug 包时（默认出正式包）
bash scripts/verify.sh --debug

# 指定版本号校验（默认读 build.gradle.kts）
bash scripts/verify.sh 1.1.0
```

`scripts/verify.sh` 默认构建 release，复制成 `dist/CloudBuddy-<versionName>-release.apk`，
并打印 versionCode / 大小 / sha256 / 签名指纹 —— **这是唯一该抄进文档的数字**，别自己另算。

正式包里额外比对证书 DN：`apksigner verify` 只验「签名自洽」，用 debug key 签也能过，
所以脚本会拒收 `CN=Android Debug` 的包。这是在 1.1.0 引入的闸门，不要删。

改这个脚本时注意 `set -o pipefail`：`grep` 没匹配到、`aapt2 ... | head -1` 里的 `head`
提前关管道，都会让脚本以 1 或 141 退出。前者已用 `|| true` 包住，后者改成先取全量再截首行；
带条件判断的 grep 一律写成 `if ... then ... fi`，别用 `cmd && {}`（grep 失败会让整个语句返回 1，
set -e 直接退出）。

- `local.properties` 必须存在且指向 `sdk.dir=/opt/android-sdk`，否则 Gradle 直接失败。
- 依赖已全部缓存，**必须带 `--offline`**；不带会去连阿里云镜像，超时后构建失败。
- 不要升级 AGP / Kotlin / Compose BOM。离线缓存里没有新版本，升了就编不过。
- 这个目录**现在是 git 仓库**，远端 `origin` 指向 GitHub（`silverwolf-lv9999/CloudBuddy`）。
  推送前先确认 `git status` 干净、`dist/` 没有被 add 进来。

## 正式签名

1.1.0 起对外只分发正式签名的包。密钥在 `keystore/cloudbuddy-release.jks`
（4096 位 RSA，别名 `cloudbuddy`，有效期到 2054 年），**该文件被 `.gitignore` 排除，
不能入库，也不要贴进任何文档**。

凭据四项写在 `local.properties`（同样不入库）：

```
release.storeFile=keystore/cloudbuddy-release.jks
release.storePassword=...
release.keyAlias=cloudbuddy
release.keyPassword=...
```

`app/build.gradle.kts` 从这份 properties 里读，缺任何一项 release 会构建成未签名包，
`verify.sh` 会因此失败——这是有意的，宁可构建报错，也不要悄悄发一个 debug key 签的「正式版」。

证书指纹（换 key 会导致老用户无法覆盖安装，且没有补救手段）：

```
SHA-256 59:29:B6:F4:7F:2B:C4:6F:B9:F5:4B:2F:15:BD:42:C4:37:55:81:76:06:38:C0:66:49:93:B8:4A:2B:46:8F:90
```

**丢失 keystore 等于这个应用再也升不了级**，只能换包名重发。备份责任在仓库所有者。

### CI 出包（GitHub Actions）

密钥同时存在仓库 Secrets 里，runner 上还原成文件、构建完随机器销毁，日志自动打码：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 keystore/cloudbuddy-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | 库密码 |
| `ANDROID_KEY_ALIAS` | `cloudbuddy` |
| `ANDROID_KEY_PASSWORD` | key 密码 |

`.github/workflows/release.yml`：推 `v*` tag 自动构建 + 自检 + 发 Release；`workflow_dispatch`
只构建不发版（用来验证流水线本身）。流程是 checkout → 校验 tag 与 `versionName` 一致 →
setup-java 17 → 探测 runner 自带 SDK → 还原密钥（`keytool -list` 先验能打开）→ `verify.sh`
→ 传 artifact → 发 Release。

**已实测**：2026-11 手动触发第 3 次全绿，7 步全部 success，产出 10.9 MB 的
release APK（`verify.sh` 里的证书 DN 校验通过，证明用的是正式密钥而不是 debug key）。
前两次失败分别是 `setup-android` 挂掉、`settings.gradle.kts` 变量作用域问题，见坑 11/12。

## 交付规则

### 版本号

`app/build.gradle.kts` 里的 `versionCode` / `versionName`。

1. **只要用户可能装上不同内容，就必须同时升这两个号**，不能复用。
2. 需求没变也要升——曾经一版内容被否、回退后用同一个 1.0.6 重新构建，
   手机上装了两份内容不同的同号包，无法区分，只能返工。
3. 产物命名跟版本走：`dist/CloudBuddy-<versionName>-release.apk`，旧的不要删（已经发过 Release 的版本要留着对照）。
4. 发布走的顺序是：升版本号 → `bash scripts/verify.sh` 全绿 → 提交推送 → `gh release create`
   带上 APK 资产和 sha256。跳过任何一步都会出现「GitHub 上的包和仓库源码对不上」。

### 文档同步

改完行为，这三处要一起改，否则下一轮会拿着旧结论干活：

| 文件 | 写什么 |
|---|---|
| `README.md` | 「功能」列表 + 「产物」块的版本号与 sha256 |
| `docs/FIXLOG.md` | 追加 `# <版本> · <一句话>` 章节：现象 / 根因 / 改法 / 验证 |
| `docs/PERFLOG.md` | 只有涉及性能时写，记录改动前后判断依据 |

**边做边写，不要最后一次性补**。每完成一个可验证的步骤就追加一条现象/结论；
上下文被截断时，`docs/FIXLOG.md` 最后一条是唯一能把工作接回来的东西。

章节模板（照抄结构，别写成流水账）：

```markdown
# 1.0.x · <一句话说清是什么坏了>

## 背景
什么场景下暴露的，用户原话怎么说的。

## 根因
贴出坏掉的那几行代码。要能让下一个人判断「我这个改动会不会又踩回来」。

## 改法
关键决策 + 为什么不用另一种做法（被否过的那条尤其要写，否则会被改回去）。

## 验证（1.0.x）
- 三件套结果、单测数、新增用例名
- APK 版本 / versionCode / sha256
- **未做真机验证的部分**，以及靠代码路径保证了什么、需要在真机上复核什么
```

### 验证口径

- `scripts/verify.sh` 通过才算完成（编译 + 单测 + lint 全绿，lint 必须 0 error，
  APK 的 versionCode/versionName 必须和 `build.gradle.kts` 一致）；
- 交付物只认 `dist/` 下那份，`app/build/outputs/apk/debug/app-debug.apk` 是中间产物；
- **真机验证只能靠用户设备**。环境里没有 KVM、没有账号，跑不了模拟器也登不进云端。
  涉及网络协议、UI 观感的改动，改完必须请用户装 `dist/` 里的包实测，结论里写清
  「谁测的、测到哪一步、哪条分支没覆盖」，不要用「代码路径上是对的」代替实测。

### 1.0.10 提问通道实测（用户设备，2026-11 补记）

从官方客户端之外唯一能验证的就是这条路：让 Agent 主动调 AskUserQuestion，
看卡片是否弹出、答案是否按协议回传。

| 用例 | 结果 |
|---|---|
| 卡片弹出（两题同卡） | 通过。两题都渲染，能点能提交 |
| 单选回传 | 通过。`answers` 里是字符串 |
| 多选多值 | 通过。勾 3 项回来 3 项且保序，是数组 |
| 未作答的题 | 不出现在 `answers` 里（客户端跳过），服务端接受并继续 |
| 连续三次提问 | 每轮都能继续，没有出现「干等 5 分钟」的静默 |

尚未覆盖的分支，下次改这块要补测：

- **自定义输入**（选项之外自己打字）——`buildAnswers` 里 `pick.custom` 优先级最高，
  会顶掉已选项，拼错就丢用户输入，目前只有单测保障；
- **权限确认** `session/request_permission`（`kind: allow_once` 这类，回包是
  `{outcome:{outcome:"selected",optionId}}` 而不是 `submitted`）；
- `answers` 的 key：云端没给 `id` 时客户端发 `q-N`，服务端是否一定认这个 key，
  只能从「问答之后对话能继续」间接推断，没有服务端侧确认。

## 代码约定

- 包结构：`data/`（协议与状态）+ `ui/`（Compose）。分层评估见 `docs/ARCHITECTURE.md`，
  **当前不建议引入 Hilt 或拆 Gradle 模块**，别自作主张加。
- 状态单一出口：`BuddyRepository` 持有 `StateFlow<AppUiState>`，Compose 只 `collect`。
- ACP 事件 → `AcpReducer`（纯状态机，可单测）→ 快照 → UI。**解析和归约逻辑必须写成纯函数**，
  不要塞进 Composable，否则没法测（`Timeline.kt`、`AskParsing.kt` 是范例）。
- 单测用真的 `org.json`，不要 mock；不引 Robolectric，不写依赖 Android framework 的测试。
- 新增行为至少配 1 个用例；改协议解析必须同时覆盖旧载荷。

## 十五个坑（都真踩过）

1. **KDoc 里不要写 `/root/*`**。`/*` 会开启嵌套块注释，一直吞到后面的 `*/`，
   编译器报错位置会离谱到完全不相干的行。同理避免 `/** ... /path/* ... */` 这种。
2. **历史回放期间不能边收边推**。用户明确否过：打开旧会话时应该「转一下 → 直接是最新样子」，
   不能看到历史一条条往上冒、工具调用还在转圈。`publishCoalesced()` 在 `replaying` 时必须返回。
3. **状态优先级不能改**。`update.sessionStatus ?? update.status ?? meta.sessionStatus ?? meta.status`。
   `_meta` 是上一轮的残留，直接读它会导致流式输出时右上角「空闲/执行中」疯狂闪烁。
4. **用户消息回显要靠 `localEcho` 吞，不能靠前缀匹配**。云端会把消息规范化
   （去首尾空白、合并空行），前缀去重必然失配，然后自己发的消息出现两条。
5. **下载必须落公共 Download**。用 MediaStore（API 29+）/ 直写 + `MediaScannerConnection`（26–28），
   失败要删掉 `IS_PENDING` 记录。写进应用私有目录等于用户找不到文件。
6. **`session/load` 是同步 RPC**。服务端要等整段历史回放完才回响应，30 秒默认超时在长会话上必炸。
   现在用 180 秒 + 静默 1.5 秒判定收尾，不要改回短超时。
7. **服务端会反向请求客户端**，带 `method` 和 `id` 的帧必须回 result。
   `receive()` 里 `if (root.has("method") && root.has("id")) onServerRequest(root)` 不能删：
   提问走 `_codebuddy.ai/question`，不回包服务端干等 5 分钟，界面表现就是「什么都没发生」。
   回包要换线程（`replyExecutor`），在 SSE 读线程里同步 POST 会自己等自己。
   1.0.10 已在用户设备实测打通（见上文「提问通道实测」），这条分支不许再删。
8. **启动探测不要把「连不上」当成「没凭据」**。`probeState()` 必须保持三态
   （`VALID` / `INVALID` / `UNREACHABLE`），只有 401、403 和 `SessionExpiredException` 才算失效；
   超时、DNS、断网、5xx 一律按离线处理、直接进主页。这条线上曾有四处
   `runCatching{}.getOrDefault(false)` 把异常吞成 false，结果网络一差就把已登录用户踢回登录页。
9. **`git push` 走 HTTP/2 会被出口网关打成 502，必须显式降级**。同一份改动
   `git push` 连续 502 三次、卡住七分钟，加 `-c http.version=HTTP/1.1` 后一次通过。
   同理 `gh release create` 带上资产也容易 502——**先建 release 本体，再
   `gh release upload` 单独传文件**，比一次做完成功率高得多；创建失败时 GitHub 会回滚，
   表现为「返回了一个 release id，但 `gh release view` 说 not found」，不要被那个 id 骗了。

   另外别用 `if git push | tail -3; then` 判断成败：管道退出码是 `tail` 的，
   push 失败了照样进 then 分支。要看 `$?`/`PIPESTATUS` 或者干脆不接管道。
10. **推 `.github/workflows/` 需要 `workflow` scope**。OAuth token 只有 `repo` 时会被拒：
   `refusing to allow an OAuth App to create or update workflow ... without workflow scope`。
   重新授权时把 `repo read:org workflow` 一起要，顺带 `read:org` 有了 `gh auth login`
   才能正常持久化（只给 `repo` 它自己会拒收 token）。
11. **`settings.gradle.kts` 里的 `pluginManagement` / `dependencyResolutionManagement`
   看不到文件顶部的 `val`**，会报 `Unresolved reference: ci`。这两个块是独立编译的，
   只能内联 `System.getenv(...)`。本地能跑不代表 CI 能跑——本地走的是 else 分支，
   CI 才第一次编到 if 分支。
12. **不要用 `android-actions/setup-android`**。`ubuntu-latest` 自带
   `/usr/local/lib/android/sdk`（含 platforms/android-35 与 build-tools），那个 action 还要
   去 `dl.google.com` 拉 cmdline-tools 再 `sdkmanager --update`，网络一抖整步失败。
   直接 shell 探测目录存在性即可，失败信息也更清楚。
13. **Actions 的原始日志从 blob 存储下载，某些出口网关取不到**（`gh run view --log-failed`
   和 `/actions/jobs/<id>/logs` 都回 502，网页端日志是动态加载的，抓 HTML 只有
   "There was an error while loading."）。要看失败原因就走 **check-run annotations**：
   普通 API 能读，所以流水线里要把失败日志尾部塞进 `::error title=...::`（换行用 `%0A`）。

14. **多账号不能重建 cookie jar**。OkHttp 的 `cookieJar` 在 client 构建时绑定，
    重建 jar 等于重建 client，正在跑的请求全部作废。做法是 jar 内部按槽切换
    （`cookies_v1:<账号 id>`），实例始终是同一个。另外「先登录再建账号」的顺序会
    把凭据写进 guest 槽，`beginAccount()` 必须 carryOver 接管，否则登录完就丢 cookie。
15. **MCP 没有云端管理接口**。官方 web 的 getMcpServers/toggleMcpServer 走本地
    agent 适配器，云端沙箱没有对应 REST。客户端只做 ACP 那一步：把配置塞进
    `session/new` / `session/load` 的 `mcpServers`。技能有真接口，路径在
    `data/SkillApi.kt` 顶部注释里，全部来自 `main-*.js` 里的常量，无公开文档。

## 协议速查

完整记录在 [docs/protocol.md](docs/protocol.md)，改协议前先读。

| 层 | 用途 | 关键点 |
|---|---|---|
| console REST | 会话列表、模型、积分签到 | 需要 `enterpriseId` 与 cookie，企业 ID 不要写死成 `personal` |
| ACP（SSE + JSON-RPC） | 对话主通道 | `initialize` → `session/load` → `prompt`；提问走 `resolveInterruption` |
| E2B envd | 沙箱文件 | `POST {endpoint}/filesystem.Filesystem/ListDir`，头 `Connect-Protocol-Version: 1` |

提问相关：`questions` 数组可能 1~4 个，`multiSelect` 表示多选；权限确认只有 `options`
没有正文（`kind` 是 `allow_once` 这类机器值）。解析统一在 `data/AskParsing.kt`。

## 文档地图

| 文件 | 什么时候读 |
|---|---|
| `docs/protocol.md` | 动网络层之前 |
| `docs/FIXLOG.md` | 改某个模块之前，先看它以前怎么坏的 |
| `docs/PERFLOG.md` | 做性能相关改动 |
| `docs/ARCHITECTURE.md` | 想重排包结构、加 ViewModel 之前（含结论：现在不拆模块） |
| `docs/NOTES.md` | 零散排查记录 |
