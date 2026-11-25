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
  - **1.5.0 例外**：为了接入 Koog（本机 agent 框架），Kotlin 已从 2.0.21 升到 **2.2.10**，
    并新增 `org.jetbrains.kotlin.plugin.serialization`（同为 2.2.10）。AGP 仍是 8.7.3、
    Compose BOM 仍是 2024.10.01，这两项没有跟着动——Kotlin 2.2.10 与它们是兼容的。
    再往上升（2.3.x）会强制连带升级 Compose BOM 与 AGP，那属于大改，不要轻动。
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

## 三十个坑（都真踩过）

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
15. **MCP 分两类，别再说「云端没有 MCP 接口」**。
    - 市场连接器（GitHub / Figma 这类，官方 App 里连的 MCP）是**账号级、云端存**的：
      `GET /console/as/connector/user/` → `data.connectors[]`，两个独立标记
      `connect_status`（绑过）与 `active_status`（启用），启停走
      `PATCH /console/as/connector/user/{name}/active_status`。官方 App 改了网页端就跟着变，
      因为两端读写的是同一条记录。授权是否还有效要另查 `POST /console/as/connector/oauth/auth-status`，
      `connect_status=1` 只表示「绑过」，token 过期后仍然是 1。
      2026-11 已用真账号实测：读到用户的 `github`（connect=1 / active=1），启停接口
      PATCH 后立刻生效，已恢复原值。市场清单是
      `GET /console/as/connector/registry2c/list?scope=all&mcp_only=true`，
      条目里的 `mcp.sandbox_url` 是沙箱内网关地址，客户端**不要**把它当 mcpServers 下发。
    - 自定义 MCP（`custom-mcp:` 那类）才是本机配置：web 的 `getMcpServers/toggleMcpServer`
      走本地 agent 适配器，没有云端 REST。客户端只在 `session/new` / `session/load`
      的 `mcpServers` 里下发，见 `data/McpStore.kt`。
    技能接口在 `data/SkillApi.kt` 顶部注释；连接器在 `data/ConnectorApi.kt`。

16. **技能市场别再走 `internal_knot-skill`**。2026-11-24 真账号实测：
    `GET /console/agent-gateway/internal_knot-skill/apigw/api/v1/skills/list` 回
    `403 {"code":17012,"msg":"knot user identity unavailable"}`，补 `X-User-Id` / `X-WB-UA`
    都没用——这个账号没有 knot 身份，不是 header 拼错。官方 web 默认走企业聚合接口
    `POST /portal/enterprises/personal/skill-market/list?source=builtin&page=1&page_size=20`，
    参数在 query、**body 必须空**（带字段的 body 回 400），搜索词是 `keyword` 不是 `search_keyword`，
    响应在 `data.items[]`，id 取 `skill_id`，标题取 `display_name_zh`。企业账号把
    `personal` 换成 `settings.enterpriseId`。`/portal/operation-platform/market/skill/list`
    对这个账号返回空列表，不能当兜底。
    **1.3.2 补充**：OkHttp 的 `method("POST", null)` 会在本地抛
    `method POST must have a request body.`，请求根本发不出去（Python requests 会自动补
    `Content-Length: 0`，抓包时看不到这个差异）。统一在 `CloudHttp.bodyFor()` 给
    POST/PUT/PATCH 的 null body 补长度为 0 的 JSON body，不要在每个调用点各自处理。

17. **回放闸门只看 `loadDone`，超时要分「软/硬」**。2026-11-24 真机三连坑：
    - 回放结束不能靠「事件静默 1.5s」判定（`watchReplay` 看门狗）。历史长、工具调用慢
      时事件间隔会超过 1.5s，提前 `endReplay()` 后剩余历史在 `replaying=false` 下逐条上屏
      =用户看到的「回放」。`publishCoalesced` 的闸门必须用 `!loadDone`，只认
      `session/load` 真正收尾（响应到达或等待超时）。
    - `session/prompt` 的响应要等整轮跑完，本地 10 分钟窗口到了 ≠ 任务失败。
      真正掐断流的是 OkHttp 的 **call timeout**（`postRpc` 里同步读 SSE 到流结束，
      `latch.await` 根本走不到）。prompt 用 2 小时兜底，超时抛 `PromptWaitTimeout`
      而不是 `SocketTimeoutException`；UI 不标 FAILED、不弹红色错误，状态交给 SSE 收敛。
    - `ui.sending` 是全局布尔值：A 会话在跑时切到 B，输入栏仍显示 A 的红色暂停按钮，
      且后台错误会贴到 B 页面。切会话（open/start/close）一律清 `sending`；发送协程
      锁定 `targetId`，用 `applyIfActive(id)` 保证状态只写回原会话。

18. **状态必须和云端对账，不能只靠本地 reducer 猜**。ACP 每条 RPC 都是一条独立的
    短命 SSE 流（响应一到就读到结束），两条 RPC 之间客户端完全收不到事件——任务在网页端 /
    另一台设备 / 云端继续跑时，本地一无所知。真机上表现为三种错位：做完还转圈、
    云端在跑却显示已停止且不出新消息、云端在跑还能发消息把上下文弄乱。
    权威来源只有 console 记录：`POST /console/as/conversations/batch-get {ids:[...]}`
    （官方 `batchGet`，一次一个 id，响应极小）。对账规则在 `data/StateReconciler.kt`
    （纯函数，有单测）：本地这一轮有流在推时以本地为准；否则采纳云端；
    「有任务 ↔ 没任务」的跨越要额外补一次历史（`silentResync`，重建连接重放）。
    性能约束（用户明确要求不能卡）：后台不轮询（`setForeground` 由 Activity 生命周期驱动）、
    有任务 10s / 本地推流 30s / 空闲 45s、全量列表 60s 一次（角标用），
    补历史按 `updatedAt` 去重并 8s→60s 退避，UI 写入只在字段真有差异时进行且加锁
    避免把 SSE 刚推上来的消息覆盖回旧快照。发送前还要用 `batch-get` 再确认一次空闲
    （`confirmIdle`），本地状态过期时直接拒发——宁可拦下，也不要把消息插进别人的轮次。
    另外本地刚收尾的 8 秒是「收敛窗口」（`SETTLE_MS`），云端记录滞后时不要把 running 盖回来。

19. **Koog 自定义模型 ID 必须显式声明端点能力**。`LLModel(id = "deepseek-chat")` 只给
    provider/id 的话，框架会拒绝执行并抛 `Cannot determine proper LLM params for OpenAI
    model`——它只认得自己内置的模型 ID，第三方 ID 推断不出用 Chat Completions 还是
    Responses。必须把 `LLMCapability.OpenAIEndpoint.Completions` 放进 capabilities。
    这条只有真发一次请求才会暴露，编译和类型检查都过得去。回归见
    `SessionAgentLoopTest`（MockWebServer 起假端点，断言 tool_call 被解析并回喂）。

20. **ktor 的 HTTP 引擎要自己引**。`ai.koog:http-client-ktor` 只依赖 `ktor-client-core`，
    引擎由调用方提供；不补 `io.ktor:ktor-client-okhttp:3.2.2` 的话编译没问题，
    运行时一调用模型就抛「找不到 HTTP client engine」。选 OkHttp 引擎是因为它依赖的正是
    项目已有的 okhttp 4.12.0，不会引入第二套网络栈。

21. **`ToolRegistry.EMPTY` 是共享单例，别在上面 `add`**。`add()` 就地改内部 list，
    对 EMPTY 用它会把工具污染到全进程所有 agent。用 DSL 构造：
    `ToolRegistry { tool(x); tool(y) }`。

22. **envd 的 token 只有 72 小时，401 不会抛异常**。2026-11-25 解出沙箱 JWT：
    `exp - iat = 259200`（3 天），`aud=sandbox-gateway`、`runtime_id` 就是 conversationId。
    过期后 envd 一律回 401/403，而 `SandboxExec` 是把非 2xx 压成 `Result.error` 的
    （不像文件网关抛 `CloudException`），所以 `withSandbox` 那种 try/catch 续期兜不住——
    表现是「隔天回来 agent 突然不会用工具了」，模型只看到一句 `[error] HTTP 401`。
    现在 `SandboxExec.run` 带 `onUnauthorized` 回调，401 时换新 token 重试一次，
    `SandboxTools` 里的 token 是 `@Volatile var`（续期后就地更新，否则下次又拿旧的去撞）。

23. **`end` 帧里的 `error` 不一定是错误**。实测 `sh -c 'echo hi; exit 7'` 回：
    `{"end":{"exitCode":7,"exited":true,"status":"exit status 7","error":"exit status 7"}}`。
    非零退出码会把 `error` 填成退出状态文本；当成执行错误处理会让模型以为沙箱坏了、
    去重试一条已经跑完的命令。`ProcessOutput.merge` 用 `EXIT_STATUS` 正则把
    `exit status N` / `status N` / `signal: ...` 排除在 `error` 之外，退出码本身照常返回。

24. **WB 网关按 User-Agent 指纹校验登录态**。同一个 cookie 换个 UA 请求立刻回
    `401 {"error":"invalid_token","data":{"msg":"登录态已失效，请重新登录"}}`。
    客户端里 UA 是固定的所以无感，但**写联调脚本必须复用登录时那条完整 UA**，
    否则会误判成「cookie 失效」白重登一次。

25. **自定义引擎建会话：不传 `model` 也能成功，且沙箱凭据是内联下发的**。
    2026-11-25 真账号实测：`POST /console/as/conversations/` 只带 `prompt:""`（连 `model`
    字段都不给）→ `code:0`，回包 `session` 里直接有 `link / token / sandboxId / cwd /
    e2bEndpoint / e2bDataEndpoint`，沙箱立即可用（Ubuntu 24.04 / Node 22 / Python 3.11），
    回包 `model` 为 `null`；`GET .../session` 也能补拉，但没必要多跑一趟。
    所以自定义模式建会话时**不传模型**（会话只当沙箱载体，云端不挂用不到的模型），
    也不需要自己拼 `49983-{id}` 端口——`e2bDataEndpoint` 直接给。
    另：沙箱 JWT 里的 `sandbox_id`（`bytfj3ev...`）和 conversationId 不是一回事，
    但 `link` 用的是 conversationId，工具寻址一律按 conversationId 走。

26. **模型是「会话的云端属性」，不是本地偏好**。官方 App 里切模型后网页端也会变，
    因为 `session/set_model` 写的就是云端 session 记录；`session/load` 的响应里会回
    `models.currentModelId`。之前的实现只存一个全局 `selectedModel`，还让它优先于云端值，
    后果有两个：切会话后界面模型与云端不符；**每轮发送前都按本地值 `set_model` 一次，
    把用户在官方 App / 网页端刚改的模型改回去**。现在的规则：
    - 显示优先级 = `acpCurrentModels[id]`（ACP 下发）→ `settings.modelFor(id)`（首屏缓存）→ 默认值；
    - 只在用户**主动切换**时调 `set_model`，成功才写缓存，失败回滚 UI；
    - 发送路径里**不要**再调 `set_model`，本轮模型随 `prompt` 参数传。
    `REST` 的会话列表/详情/`.../session` 都**不带** `model` 字段，别去那里找。

27. **引擎是本地概念，必须按会话存；换引擎要迁历史**。云端没有"本机推理"这回事，
    所以 `conv_engine:<id>` 只能本地记（注意带 `a:<账号>:` 前缀）。不做跨引擎迁移的话：
    一条用云端跑过几十轮的会话切到自定义后，本地 `AgentMemory` 是空的（模型失忆），
    界面还会因为 `restoreCustomConversation` 读到空列表而变空白。
    `seedCustomHistory()` 在切换时回放一次 ACP、把 USER/ASSISTANT 文本写进本地 memory
    （跳过 THOUGHT/TOOL：过程不进上下文），已有历史时不覆盖。反向切回云端要提示
    「自定义那几轮只在本机」——云端根本没记录，不提示用户会以为消息丢了。

28. **envd 的响应流可以边读边出，但帧必须按 5 字节头逐帧读**。
    `process.Process/Start` 是 server-streaming，`response.body?.bytes()` 会等到命令
    结束才返回（`npm install` 就是几十秒黑屏）。改成 `source.request(5)` →
    `lengthOf(header)` → `readByteArray(len)` 才能实时。三个细节：
    ① `request()` 返回 false 表示 EOF，不是"读满 5 字节"；
    ② 帧头长度解错位后会一直读出垃圾，`len<=0 || len>8MB` 必须直接收尾；
    ③ 网关断流是常态，已收到的帧要保留——整条结果作废等于把用户眼前的输出吞掉。

29. **命令输出不能无上限往内存里塞**。`cat` 一个大文件、`npm install` 刷屏，
    都能把手机堆打满。两道闸：单条输出首尾各留一半（中间插"省略 N 字符"，
    只留尾部会丢报错开头、只留头部会丢最终结论），整个会话只留最近 200 条。
    另外 `AgentMemory` 和命令日志都是按会话存的，**删会话时必须一起 forget**，
    否则几十个已删沙箱的输出会常驻到进程结束。

30. **改包名做共存版：只动 `applicationId`，别动 `namespace`**。
    `namespace` 只决定 R / BuildConfig 的包路径，与安装身份无关；跟着改就要动
    全项目 `package` 声明，纯属自找麻烦（Manifest 里的 `.MainActivity` 按 namespace
    解析，改了反而容易错）。真正会因共存冲突的只有三处，且都已自动跟随：
    FileProvider 的 `authorities="${applicationId}.fileprovider"`、`Downloads` 里的
    `context.packageName + ".fileprovider"`、AndroidX startup 的 provider authority
    （构建自动加前缀）。改完必须核对 `aapt2 dump badging` 的 `package:` 与
    `application-label:`，以及 `dump xmltree` 里 provider authorities 是不是新包名——
    authority 撞了构建期不报错，只在安装或运行时炸。
    另：包名一换 `SharedPreferences` 目录随之更换，**原版登录态/设置不会带过来**；
    `UpdateChecker` 指向上游仓库，那边发的是原版 APK（包名+签名都不同），
    对改版必须关掉（见 `SettingsScreen.FOLLOWS_UPSTREAM`）。

31. **会话类型（云端 / 本机）是创建时的事实，不能当运行时可切换的开关**。
    1.5.4 之前允许把已有云端会话现场切成本地 agent，结果是同一条会话里
    云端记录只有前半段、本机 `AgentMemoryStore` 只有后半段，两边都对不上号。
    现在的规则：`AppSettings.kindOf(id)` 决定归属，**没存过归属的一律算云端**
    （否则用户改了默认引擎，几十条老会话会被集体误判）；`useCustomEngine()`
    只对草稿生效。判定归属千万别回落到全局 `engineMode`——那是「新会话默认值」，
    不是「这条会话是什么」。
    另外 `send()` 与 `sendCustom()` 要互相兜底重定向，界面分流漏一条路就会
    把本机历史推进云端会话。

32. **两套模型的 ID 是两个命名空间，不能互相回落**。本机会话的模型 ID 属于
    用户自己的端点（`deepseek-chat`），云端那套（`hy4-preview`）在第三方接口上
    根本不存在。所以 `AppSettings.customModelFor(id)` 只回落到 `customModelId`，
    绝不碰 `selectedModel`；`refreshModels()` 也要在 `isLocal` 时短路，
    否则云端清单一刷新就把界面上的本机模型名换成云端模型。
    存储上可以共用 `conv_model:` 这个 key——类型互斥，不会同时被读。

33. **模型列表这类「拉一次给用户点」的数据要进 UI 状态，不能只写 SharedPreferences**。
    SharedPreferences 的写入不会触发 Compose 重组：拉完 `GET /models`，
    弹窗里还是空的，用户只会觉得按钮没反应。`AppUiState.customModels` 是为此加的。

34. **本机会话的「状态」只能算在本机，绝不能采信云端角标**。它的云端记录是一具空壳
    （从没收到过 prompt），状态位要么恒为 null，要么是它还当云会话时的残值。
    采信了就会出现「本机 + 云端执行中」永久挂着的僵尸角标，而且后台对账每次都会
    建一条临时 ACP 连接去重放空历史。现在的口径：`Conversation.asLocal()` 按
    「本进程里 `customJobs` 有没有它的 job」算状态，`reconcile()` 对这类会话直接返回。
    源头也堵了：**用空 prompt 建会话时不要把 `prompt` 字段发出去**——
    带 `prompt: ""` 服务端会当成一次真实提问开跑，而这条会话没挂模型，那一轮永远跑不完。

35. **思考参数不是标准字段，而且不能在 ktor 的 client 级钩子里加**。
    1) Koog 的 `LLMParams.additionalProperties` 会被序列化成嵌套的 `additional_properties`
    对象（0.6.0 实测），服务端不认；2) Koog 拿到传入的 `HttpClient` 后会 `client.config{}`
    派生新实例，`HttpSend` 拦截器与 `onRequest` 钩子都会丢——只有 **OkHttp 引擎配置**
    （`engine { addInterceptor(...) }`）会被继承。所以思考字段统一在
    `data/CustomLlmClient.kt` 的 `ThinkingInterceptor` 里并进请求体，别处不要再动 body。
    3) 读 body 用了 `writeTo(buffer)`，原 RequestBody 就废了，必须用读出来的内容重发。
    4) 档位与写法的对应关系见 `ThinkingParams`：`thinking` 那套关闭要显式发 `disabled`，
    `reasoning_effort` 那套没有「关闭」值（关就一个字都不发），`gpt-4o`/`gpt-4.1`
    收到 `reasoning_effort` 会 400（`looksClassic` 已短路）。

36. **打开模型弹窗不要顺手打用户自己的端点**。`GET /models` 走的是用户付费的第三方接口，
    模型清单很少变，设置页手动拉一次就够；弹窗只负责「点一个用哪个」。
    云端会话的「重新加载」是免费的账号接口，保持自动无妨。

37. **`/models` 是目录，不是配置**。拉取结果只能进候选池（`AppUiState.customCandidates`），
    绝不能覆盖「我的模型」（`settings.customModels`）：用户改过的显示名、思考强度、
    系统提示词都挂在模型条目上，一次刷新把它们冲掉，等于白改。开源客户端（RikkaHub）
    同样是 provider 下挂 model 对象、拉取只做选择器的数据源。

38. **模型级覆盖项的空值代表「跟随全局」**，不是在保存时把全局值抄进条目。
    抄进去的后果是：用户后来改了全局思考强度，那些「看起来没设置过」的模型还停在旧值上，
    而界面上根本看不出差异。解析统一走 `AppSettings.customModel()` 的
    `entry?.x?.takeIf { ... } ?: globalX`。

39. **改存储格式要在 decode 里做兼容，别换 key**。`custom_models` 从
    `["deepseek-chat"]` 变成对象数组，靠 `CustomModel.decode` 同时认字符串与对象；
    换新 key 会让升级上来的用户「模型列表空一次」，而这种空态看起来像 bug。

40. **设置二级页里的值必须「改完立刻写回」，不能只改 `remember` 的副本**。
    拆分后页面一离开就销毁，本地状态全丢；现在每个开关/输入框都是立即写 `settings.*`，
    所以丢掉的是「拉取中」「测试结果」这类瞬时状态，重进重置反而是对的。
    以后加设置项时若引入「保存按钮」式的延迟写入，得同时解决页面销毁丢改动的问题。

41. **终端输入框用 `TextFieldValue` 而不是 `String`**。快捷符号键要把字符插到**光标处**，
    `String` 拿不到选区；`TextFieldValue` 的 `selection` 才能算出插入位置，插完还要把
    光标移到新字符之后，否则连点两个键顺序会反。历史翻页也要 `histIndex = -1` 复位，
    不然用户手改过的内容会被下一次 ↑ 覆盖。

42. **实时输出只能渲染尾部**。`TerminalState.liveOutput` 每来一帧就变，整段排版会让
    列表掉到个位数帧率（`npm install` 一次几十万字符）。`takeLast(6000)` 不只是省内存，
    是省布局；完整内容在 `CmdEntry` 里，跑完照常显示。

43. **终端/设置这类「一页很长」的界面，别把 `LazyColumn` 套进 `Column` 的可滚动区域**。
    设置二级页统一用 `SettingsPageScaffold` + `SettingsList`（内部是 `LazyColumn`），
    条目一律是 `item {}`；往里面塞 `Column(Modifier.verticalScroll())` 会出现
    「无限高度约束」崩溃。

44. **"正在跑的输出"必须有独立归属，不能只活在 UI 状态里**。`liveOutput` 是为了性能
    才存在的（每个 chunk 全量刷新条目列表会把 LazyColumn 拖垮），但它有个致命副作用：
    协程被取消时 `SandboxExec` 直接抛 `CancellationException`，`Result` 拿不到，
    `stdout` 是空的——**用户按停等于清屏**。现在用 `shellLive` 单独累积，停止时落盘。
    以后任何"边跑边显示"的功能都要问一句：中途被打断，已收到的部分去哪了？

45. **取消之后要按世代号丢弃结果**。`job.cancel()` 之后协程不一定立刻死：
    `runCatching` 会吞掉 `CancellationException`，后面的同步代码照常执行。
    没有 `shellGen` 的话，用户按停那一刻会同时写入「已中断的记录」和「协程回来记的空记录」，
    屏幕上出现两条一模一样的命令。

46. **中断不是错误**。别塞进 `error` 字段：`ping` 跑十秒按停，那十秒输出是有效结果，
    标成红字 `[error]` 会让用户以为命令失败了。用独立的 `aborted` 标记，
    `ok` 判定里排除它，颜色走琥珀。

47. **终端输出必须过 ANSI 解析再画**。`ls --color`、`git status`、`npm` 全都带 `\e[32m`，
    原样画出来就是 `[32m` 方括号乱码。另外 `\r` 要按"回行首覆盖写"处理（逐段覆盖），
    只取最后一段会丢内容，不处理则进度条会叠成一行。复制出去要用 `Ansi.plain()` 剥掉。

48. **长列表别一次性 layout**。`cat` 一个几万行的日志，`Text` 一次布局几万行会卡死，
    折叠阈值挡不住"用户点了展开"。展开要按页给（现在 400 行一页）。

49. **命令日志的分区键必须是「会话#标签」，不能是裸会话 id**。多标签上线后，
    共用一把键会让所有标签看到同一份记录，表现是「在标签 2 敲的命令出现在标签 1」。
    `forget()` 要按前缀清，且分隔符不能用 `-` 之类：`c1` 会误伤 `c10`，
    用 `#` 才能保证前缀匹配不出错。

50. **执行槽按标签索引，别用单例字段**。`shellJob` 单槽时多标签一切换就
    互相打断或抢着停。现在 `shellRuns: Map<tabId, ShellRun>`，`tabId` 全局自增
    ——跨会话重号会让两个标签抢同一个槽。

51. **后台标签的输出要收，但不要刷 UI**。`publish(tabId)` 只改屏幕上那个标签；
    后台的照常写进 `ShellRun.live`，切回来由 `selectTerminalTab` 接上。
    反过来（后台也刷 `_terminal`）会让整个列表每秒重组几十次。

52. **每个标签一份 `LazyListState`**。共用一个的话，切标签要么被拽到底、
    要么停在上一份内容的偏移量上，两种都像 bug。用 `map[tabId]` 缓存实例。

53. **"要不要跟随最新输出"不能用几何状态实时判定**。实时输出是同一个 item 在变高，
    内容一变长 `canScrollForward` 就是 true，实时判定会立刻认为"用户在看历史"并停止跟随，
    表现就是视图钉在输出开头。正确做法：只在**用户手势结束**（`isScrollInProgress` false）
    时更新一个 `follow` 标志，内容增长不参与判定。

54. **`scrollToItem(Int.MAX_VALUE)` 是个坑**。条目数还没更新时它会被 coerce 到 0，
    看起来像"跳回开头"。要滚到底就先 `withFrameNanos { }` 等一帧布局，
    再用 `layoutInfo.totalItemsCount - 1`。

55. **设置页合并优先于拆分**。两个二级页互相跳（推理页里有"去配端点"的入口），
    比一个长页面更绕。判断标准是"用户是不是要来回看同一件事"——引擎选择和端点配置
    就是一回事的两面，合并成「模型与推理」一页反而短了。

56. **用户找一个"栏目"，不是找一张卡**。设置页缺「账号与额度」被报了两次：
    第一次我把积分签到做成一张独立小卡挂首页，用户仍说"没有这一栏"。因为栏目 =
    分组标题 + 成组的内容，一张没标题的卡不在扫描路径上。现在首页第一组是
    `SectionTitle("账号与额度")` + 一张两段卡（账号 / 积分签到）。
    以后加"某某栏"先加标题，别只加内容。

57. **重复的判定标准是"同一句话出现两次"，不是"两页都有相关内容"**。
    同一件事的「摘要 / 明细」是分层，可以两页都有；但余额、签到按钮、某条额度
    原样出现在两处就是重复——用户会问"到底哪个算数"。写完一页回头搜一下这些
    字面量在前一页出现过没有。1.6.5 到 1.6.6 就是为这个返工了一次。

58. **列表卡顿先看两样：阴影与 contentType**。
    `Card` 默认 1dp elevation，滚动时每帧重绘模糊脏区，一屏十几张就够掉帧，
    设置页统一换 `Surface`（0 阴影，靠 surface/background 色差分层次）。
    其次，不同高度的 item（分组标题 vs 卡片）必须给不同的 `contentType`，
    否则 LazyColumn 复用槽位时会在两者之间反复丢弃重建组合树。

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
