# CloudBuddy 修复日志

## 本轮任务（用户反馈）
1. **无法选择模型**
2. **工作区打开 404**

## 约束
- 保留官方云端沙箱工作区（cwd=/workspace），不退化成本地 API 反代。
- 每步执行都记录日志，防止上下文压缩丢失信息。

---

## 1.2.0（多账号 / 技能 / MCP / 折叠方式）

**多账号**：`accounts_v2` 存账号表，每样跟账号绑定的值带 `a:<id>:` 前缀，cookie 按
`cookies_v1:<id>` 分槽。切账号只换活跃 id 与槽位，OkHttp 持有的 jar 实例不变——
重建 jar 等于重建 client，正在跑的请求全废。首次升级时把老的 `logged_in` /
`cookies_v1` 一次性迁到 `a1`，用 `accounts_v2` 是否存在做幂等标记，否则用户删光
账号后旧数据会复活一个。

**技能**：走官方 `user-asset` 接口（清单 `skill/get-list`、启停
`assets/configuration`、安装 `skill/install`、市场 `internal_knot-skill`）。路径是从
官方 web 打包产物里打捞的常量，没有公开文档，字段解析取多个候选键。

**MCP**：云端**没有** MCP 管理接口——web 端的 getMcpServers 走本地适配器。客户端只做
协议允许的那一步：把本机配置塞进 `session/new`、`session/load` 的 `mcpServers`。
界面上把这条边界写清楚，避免用户以为它和账号同步。

**折叠**：`FoldMode.BODY` 只折 THOUGHT/TOOL，正文（含过程叙述）全部保留；
`Steps.ordinal` 让一轮出现多个折叠条，展开状态改按渲染 key 存，
原来按轮次存会让同轮多条联动。

---

## Step 1 · 现状盘点
- 工程路径：`/workspace/CloudBuddy`
- 已有产物：`dist/CloudBuddy-1.0.0-debug.apk`（17.5 MB，debug 签名）
- 数据层：`WorkBuddyApi.kt` / `SandboxFs.kt` / `AcpConnection.kt` / `AcpReducer.kt` / `BuddyRepository.kt` / `Models.kt` / `AppSettings.kt`
- UI 层：`ChatScreen.kt` / `WorkspaceScreen.kt` / `SettingsScreen.kt` / `SessionsScreen.kt`
## Step 2 · 根因定位（已用官方 Web 端 JS bundle 交叉验证）

抓取 `https://www.workbuddy.cn/app` → `main-B7uclR9d.js`（17 MB）与 `shared-account-http-*.js`，
并对照 Wearbuddy 参考实现 + E2B envd 公开文档，得到以下确凿结论：

### 问题 2「工作区打开 404」
- **参考实现里根本没有目录枚举 REST 端点**：Wearbuddy `SandboxFiles.kt` 只实现了
  `GET {endpoint}/files?path=...` 单文件读取。我之前 `SandboxFs.list()` 探测的
  `/filesystem/list`、`/filesystem.list`、`/files/list`、`/files?path=...`、`/list`
  全是推测路径 → 全部 404，最终把最后一个 `HTTP 404` 抛给界面。
- **官方真实列目录协议**（`main-B7uclR9d.js` 中 `CloudFileManager.listDirectory`）：
  ```
  POST {dataEndpoint}/filesystem.Filesystem/ListDir
  Content-Type: application/json
  Connect-Protocol-Version: 1
  X-Access-Token: {session.token}
  body: {"path":"/workspace","depth":1}
  → {"entries":[{"name","path","type","size","mode","modifiedTime",...}]}
  ```
  同一套 Connect RPC 还有 `MakeDir` / `Remove` / `Move` / `Stat`。
  我原 `mkdir/remove` 用的 `/filesystem/make_dir` 等也全是猜测 → 同样 404。
- **`/files` 读取必须带 `username`**：官方 `r2r()` =
  `{endpoint}/files?path={encodeURIComponent(path)}&username={root|user}`，
  由路径决定（`/root*` → `root`，其余 → `user`）。我的 `read()` 没带该参数。
- **`type` 取值**：数字 `2` 或字符串 `FILE_TYPE_DIR` / `FILE_TYPE_DIRECTORY` 表示目录；
  `modifiedTime` 是 ISO 字符串或 `{seconds,nanos}`。我原来的解析都不认，
  即使换成正确端点也会把目录当文件、时间显示为 0。
- **文件网关地址推导**（官方 `w2r/b2r/v2r`）：优先 `e2bDataEndpoint`；否则
  `https://49983-{sandboxId}.{link 去掉首段后的域名}`。我的实现少了
  `sandboxId` 优先，也没有在 401/403/404 后刷新 session 重试（官方有此恢复逻辑）。

### 问题 1「无法选择模型」
- 模型 REST 路径本身没错：`/console/enterprises/{enterpriseId|personal}/models`。
  但官方 `AccountService` 会给所有请求注入 `X-User-Id` / `X-Enterprise-Id` /
  `X-Tenant-Id`（企业账号下服务端据此过滤模型），我的 `CloudHttp.headers()` 没有这些头；
  且 `enterpriseId` 恒为写死的 `personal`，从未从账号信息回填。
- `refreshModels()` 用 `getOrDefault(emptyList())` **静默吞掉全部异常**，界面只显示
  「账号尚未返回可用模型」，没有任何错误与重试入口；草稿会话 `activeId == null` 时
  `selectModel()` 直接 return，选了也不生效、也没有反馈。
- 更可靠的模型来源被漏掉了：官方 `extractAvailableModels()` 直接从 **`session/load`
  的 ACP 响应**读 `models.availableModels` / `models._meta["codebuddy.ai"].availableModels`
  与 `models.currentModelId`。只要 ACP 能连上（用户能正常聊天），就一定能拿到模型列表。
- 切换方法 `session/set_model` 与官方一致，确认无误。

## Step 3 · 修复方案
1. `SandboxFs`：改用 Connect RPC `ListDir/MakeDir/Remove`；`/files` 补 `username`；
   重写条目解析；对齐 `dataEndpoint` 推导；401/403/404 刷新 session 后重试一次。
2. `AcpConnection`：从 `session/load` 响应提取 `availableModels` + `currentModelId`。
3. `BuddyRepository` / `WorkBuddyApi`：账号回填 enterpriseId、补请求头、
   模型列表三路合并（ACP 主 / REST 次 / 兜底），暴露错误与加载态、支持重试、
   草稿态也能选模型并给出可见反馈。
4. UI：`ModelDialog` 增加加载态、错误、重试、手动输入模型 ID；`WorkspaceScreen`
   增加诊断与「重新获取会话」。
5. 补单测 → 重新 assembleDebug / test / lint → 产出新 APK → 更新文档。

## Step 4 · 实际改动清单

| 文件 | 改动 |
| --- | --- |
| `data/SandboxFs.kt` | 重写。列目录/建目录/删除改走 Connect RPC `filesystem.Filesystem/{ListDir,MakeDir,Remove}`；`/files` 读写补 `username`（`/root`→root，其余→user）；条目解析支持 `type` 数字/枚举、`modifiedTime` ISO/`{seconds,nanos}`、多种信封；`dataEndpoint` 按官方 `w2r/b2r/v2r` 推导（优先 `sandboxId`）；解析逻辑抽成纯函数 `SandboxEntryParser` 便于单测 |
| `data/Models.kt` | 新增 `AcpModels`：解析 `session/load` 响应的 `models.availableModels` / `_meta.codebuddy.ai.availableModels` / `currentModelId` |
| `data/AcpConnection.kt` | 新增 `onModels` 回调，load 响应到达时把云端可用模型推给编排层 |
| `data/WorkBuddyApi.kt` | `account()` 回填 `uid`/`enterpriseId`；`listModels()` 依次尝试企业 ID 与 personal、宽容解析多种字段名、失败抛可展示错误；`parseSession()` 支持 `e2bEndpoint`/`sandboxLink` 别名 |
| `data/CloudHttp.kt` | 请求头补 `X-Requested-With`、`X-User-Id`、`X-Enterprise-Id`、`X-Tenant-Id` |
| `data/AppSettings.kt` | 新增 `userId` |
| `data/BuddyRepository.kt` | `AppUiState` 增 `modelsLoading`/`modelError`；模型三路合并（ACP 主 / REST 次 / 官方默认兜底）；`selectModel()` 草稿态也能选并给出可见反馈；`connection()` 挂 `onModels`；工作区统一走 `withSandbox()`，401/403/404 自动刷新会话重试一次；新增 `reloadSessionAndWorkspace()` 与详细诊断 |
| `ui/screens/ChatScreen.kt` | `ModelDialog` 增加加载态、错误提示、重新加载、手动填写模型 ID；列表限制高度可滚动；显示模型名称与 ID；打开时主动刷新 |
| `ui/screens/WorkspaceScreen.kt` | 错误页可滚动、显示完整诊断，新增「重新获取会话」按钮 |
| `MainActivity.kt` | SnackbarHost 提到最外层，聊天页也能看到模型切换结果（此前事件提示在聊天页完全不可见） |
| `app/build.gradle.kts` | versionCode 2 / versionName 1.0.1 |
| 新增单测 | `SandboxFsTest.kt`（6 个）、`AcpModelsTest.kt`（3 个） |
| 文档 | `README.md`、`docs/protocol.md`、`docs/NOTES.md` 同步修正协议与结论 |

### 期间踩到的坑
- **Kotlin 嵌套块注释**：KDoc 里写 `/root/*` 会开启一个嵌套注释，外层 `/**` 直到
  代码里 `"*/*"` 字符串中的 `*/` 才闭合，导致 `SandboxFs.kt` 整个类解析失败、
  编译器从 `header("Accept", "*/*")` 那行开始报一串 "Expecting a top level declaration"。
  把注释改成 `/root/ 开头用 root` 后解决。这是本轮两次编译失败的唯一根因。
- `padding(bottom = …, horizontal = …)` 不是合法重载，改为 `start/end/bottom`。

## Step 5 · 验证结果

```
:app:assembleDebug :app:testDebugUnitTest :app:lintDebug   →  BUILD SUCCESSFUL
单测 26 个全部通过（原有 17 + 新增 9）；lint 0 error
aapt2 dump badging   →  versionCode=2 versionName=1.0.1 minSdk=26 targetSdk=35
apksigner verify     →  Signer #1: C=US, O=Android, CN=Android Debug（debug 签名）
```

产物：`dist/CloudBuddy-1.0.1-debug.apk`（17,322,851 字节）
sha256 `a7a3a812316dc9419928a106b70bfeb03b8546c8dd3c988671613b1d2d3a57c6`
旧的 1.0.0 APK 已移除，避免混淆。

## Step 6 · 仍需真机确认（无账号 / 无 KVM，无法在此环境验证）

1. `ListDir` 在你的账号沙箱上是否需要额外的 `Authorization: Basic` 用户头
   （官方 Web 端未带，envd 默认 user；七牛/E2B 文档示例会带 `dXNlcjo=`）。
   若返回 401，工作区错误页的「重新获取会话」可先排除地址过期；仍失败则需加该头。
2. 企业账号的模型列表是否还需要别的身份头或 `repos[]` 参数。
3. 沙箱长时间空闲后是否过期、重新进入会话后 49983 地址是否变化。
4. 长连接在息屏/切后台后的存活时间。

工作区错误页现在会打印 ACP 接入点、文件网关、沙箱 ID、cwd，以及每个候选的具体
状态码；模型对话框会显示 REST 失败原因——真机上若仍异常，把这两处文本发回来即可定位。



---

# 1.0.2 · 流式输出闪烁与会话状态错误

用户反馈三个现象，实测根因是三个独立 bug 叠加：

## 现象 1：顶部状态在「空闲 / 执行中」之间反复横跳

**根因**：ACP 的 `session/update` 事件里，状态挂在 **`update.sessionStatus`**（其次是 `update.status`），
`_meta["codebuddy.ai"]` 里也有一份但可能是**上一轮的残留值**。官方 `UKr()` 明确是：

```js
update.sessionStatus ?? update.status ?? meta.sessionStatus ?? meta.status
```

我只读了 `_meta["codebuddy.ai/status"]`，于是两层不同步时状态就在 working / idle 之间抖动。

**修复**：`AcpReducer.statusOf()` 按官方优先级取值；并且 `updateState()` 增加保护——
本轮仍在进行（`turnActive`）时，一条「空闲」上报不能把状态降级。

## 现象 2：自己发出的消息显示闪烁 / 跳动

两个原因：

1. **回显去重靠前缀逐片比对**。云端会对 prompt 做规范化（裁空白、附附件说明、合并隐藏上下文），
   一旦前缀对不上就 `expectedUserEcho = null`，然后把同一条消息**再追加一遍**。
   → 改为：本轮已经 `beginTurn()` 落地成消息的，云端回显一律吞掉（`localEcho` 标志）。
2. **`state_update` 无条件 `boundary = true`**。这并不是文本边界，却会切断流式拼接，
   导致一段回答被拆成多条消息，界面上表现为文字一会儿出现一会儿被替换。
   → 改为只有终态（DONE/FAILED/STOPPED）才切边界。

## 现象 3：返回主页后所有会话都显示「已完成」

**根因**：`TurnStateParser.remote()` 的兜底是 `return TurnState.DONE`，而字段名又漏了
`sessionStatus`（官方 `toAgentState()` 是 `const n = t.sessionStatus || t.status`）。
拿不到 `status` 就落到兜底 DONE，于是**每个会话都是「已完成」**。

原测试还把这条错误行为固化了（`missing status falls back to done`）。

**修复**：优先读 `sessionStatus`，兜底改为 `IDLE`（与官方 `wVs()` 的 default 分支一致），
并补齐官方状态词表（`working` / `streaming` / `planning` / `waiting_permission` /
`waiting_question` / `terminated`）。

## 同时修复的放大因素

- `AcpConnection` 推送按 60ms 合并（trailing 补发保证末片不丢），终态强制推送。
- `BuddyRepository.publish()` 两次 `_ui.value` 赋值合并为一次；纯文本分片不再重建整个会话列表。
- 聊天页 `animateScrollToItem` → `scrollToItem` + 用户上滑时暂停跟随。
- Markdown 逐行 `Regex(...)` 现编译 → 提为文件级常量。

## 回归测试

- `sessionStatus takes priority over status` / `missing status falls back to idle`
- `update sessionStatus wins over stale meta status`
- `idle report during a live turn does not demote the state`
- `state update mid-stream does not split the assistant message`
- `user echo with normalized text is not duplicated`

---

# 1.0.3 · 按 Wearbuddy 补齐账号功能 + 消息折叠

参考 `Oride-it/Wearbuddy` 已有的实现（其接口均已在其代码中验证过），补齐积分/签到，
并按需求加入消息折叠。

## 1. 积分余额与每日签到

### 接口依据（原文摘自 Wearbuddy `WorkBuddyCloudClient.kt`）

| 用途 | 请求 | 说明 |
| --- | --- | --- |
| 签到状态 | `POST /billing/meter/checkin-activity-status` | 官方 Web 端所用；失败时兜底 `POST /billing/meter/checkin-status` |
| 领取签到 | `POST /billing/meter/daily-checkin` | 成功后本地置 `todayCheckedIn = true` |
| 积分余额 | `GET /activity/growth/energy` | 返回 `{ balance }` |
| 等级/成长 | `GET /v2/activity/growth/profile` | 返回 `level` / `level_name` / `completed` / `total` |
| 额度包 | `POST /billing/meter/get-user-resource-summary` | 1.0.1 已接入，本次并入积分区块 |

路径前缀与本地 `CloudHttp` 一致（`baseUrl + path`，无额外前缀），已核对两边实现。

### 字段兼容

签到响应同时兼容下划线/驼峰：`today_checked_in` / `todayCheckedIn`、
`streak_days` / `streakDays`、`daily_credit` / `dailyCredit`、`today_credit` / `todayCredit` / `credit`。

### 失败策略

签到属于附属信息，**失败不抛错、不阻塞页面**：`checkinStatus()` 两个候选都失败时返回
默认值，界面显示「加载中或暂不可用」并保留 `checkinError` 文案；领取失败弹提示并允许重试。
`growth()` 两个接口任一可用即可展示。

### 展示位置

- 设置页「积分与签到」区块：余额 + 等级 + 签到按钮 + 连续签到天数 + 今日所得 + 额度包。
- 会话列表页副标题：`昵称 · 云沙箱 · N 积分`。

## 2. 消息折叠

- 折叠态放在 `AppUiState`（`collapsed: Set<String>` + `collapseAll: Boolean`），
  不污染 ACP 归约结果，也不进入消息数据类——避免影响流式拼接与快照缓存。
- 判定可折叠：正文 > 240 字或换行 > 8 行；工具行与思考行不参与（思考已有自己的展开逻辑）。
- 折叠渲染：助手消息只渲染前 5 个 Markdown 块（保留样式，不是粗暴截断字符串）；
  用户消息限制 4 行 + 省略号。
- 操作区（仅长消息出现）：「复制」+「展开全文 / 收起」。
- 三点菜单首项：「折叠全部消息 / 展开全部消息」，按 `collapseAll` 双向切换。
- 单独展开一条时把全折叠状态**固化为显式集合**再移除该条，避免「点一条、全展开」。
- 切换/新建/关闭会话时清空折叠状态（消息 id 在不同会话间会重复）。

## 3. 顺带修正

- 会话列表副标题改为拼接式（昵称 / 云沙箱 / 余额），空值自动省略，不再出现「· 云沙箱」前导分隔符。
- 设置页原「云端环境」卡片里的积分额度移到「积分与签到」区块，避免重复展示。

## 验证

- `:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` 全绿，33 个单测通过，lint 0 error。
- APK：`1.0.3` / versionCode 4 / minSdk 26 / debug 签名，
  sha256 `7c288cf0b2ef37a2fd84ab5f15f50ef17ed12287f4637ca01a4b60a213f1dc09`。
- 未做真机验证（无 KVM、无账号）。签到/余额的真实返回结构以接口实测为准，
  若字段名有出入，界面会显示 `checkinError` 原文便于继续对齐。

---

# 1.0.4 · 折叠「中间步骤」而不是正文

## 需求澄清

1.0.3 我按「折叠单条长消息正文」实现，理解错了。真实需求是：
**只保留用户提问与最终回答，中间的思考、工具调用、过程叙述全部收进一个可展开的组。**

## 依据：AgentsAnywhere 怎么做

`aa/android/.../sessiondetail/SessionMessages.kt` + `TimelineReadingState.kt` 的做法是
**分组 + 组头摘要 + 默认折叠**，不是折叠正文：

1. `groupTimelineMessages()` 把**连续**的同类事件聚合成 `TimelineRenderItem`：
   `ToolRun`（连续工具调用）、`AgentCalls`（连续 agent 调用，≥2 条才成组）、`Reconnect`。
   其余（用户消息、最终回答）保持 `Single`。
2. 组头只渲染一行：`Chevron + 图标 + 摘要文字`，例如 `toolRunSummary(messages)`；
   运行中图标带 shimmer，失败变错误色。
3. 展开状态在 `TimelineReadingState`：`expanded: Map<messageId, Boolean>` +
   `groupMembers: Map<kind:messageId, Boolean>`，`groupExpanded()` 先看显式值。
4. 切换折叠时调用 `pauseAt(header)` 记录锚点，避免高度突变把用户视角甩走。

结论：折叠的粒度是「一轮里的过程事件」，摘要行是入口，用户消息与最终回答不受影响。

## 本项目的实现

### 1. 消息带轮次

`ChatMessage` 新增 `turn: Int`。`AcpReducer` 维护 `turnSeq`：
- `beginTurn()` 递增（用户本轮提问归入新 turn）；
- 历史回放中遇到新的 `user_message_chunk`（且不是本地 echo）时也递增，
  这样加载历史会话后同样能按轮分组。

### 2. 投影层（纯函数，可单测）

新增 `ui/components/Timeline.kt`：

```kotlin
sealed interface ChatItem { val key: String
    data class Single(val message: ChatMessage) : ChatItem
    data class Steps(val turn: Int, val steps: List<ChatMessage>) : ChatItem
}
fun projectTimeline(messages: List<ChatMessage>): List<ChatItem>
```

规则：
- 用户消息 → 永远是 `Single`；
- 同一 turn 内，**最后一条非空助手消息**是「最终回答」→ `Single`；
- 该 turn 其余的消息（思考、工具、更早的助手叙述）→ 合成一个 `Steps`；
- 没有助手消息的 turn（还在跑）→ 全部为 `Steps`。

### 3. 折叠状态

`AppUiState.stepsExpanded: Map<Int, Boolean>`（显式值，按 turn）。
渲染时的默认值：**本轮正在执行 → 展开**（否则看不到进度），**其余 → 折叠**。
这样「默认只看最终回答」，同时流式过程仍然可见，符合直觉。

三点菜单首项：`折叠全部中间步骤` / `展开全部中间步骤` → 把每个 turn 写成显式值。

### 4. 移除正文折叠

上一版 `collapsed` / `collapseAll`（折叠消息正文）删除，避免两个「折叠」概念打架。
`MessageRow` 只保留长消息的「复制」。

### 5. 顺带的性能收益

折叠态下每个步骤组只渲染一行摘要，不再解析其中的 Markdown，
长会话的测量高度与解析开销都随之下降。

## 验证

- `:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` 全绿，
  **44 个单测通过**（新增 9 个投影测试 + 2 个轮次分配测试），lint 0 error。
- APK：`1.0.4` / versionCode 5 / minSdk 26 / debug 签名，
  sha256 `2ccac1ee7dab058b052d302cbcade832f3f5b885252c9a620f84248c18fa6555`。
- 未做真机验证（无 KVM、无账号）。

## 实现中踩到的两个点

1. 空白助手消息（流式首帧、或云端补的空分片）会占一个渲染项，还会把真正的回答挤进步骤组。
   → 投影时直接过滤 `ASSISTANT && text.isBlank()`。
2. `Steps` 的 key 用 `steps:$turn`、`Single` 用 `msg:$id`，从「整轮都是步骤」过渡到
   「出现最终回答」时 key 会变、LazyColumn 需要重建该项。这是预期的：
   项的内容本来就换了，保持同一 key 反而会让滚动位置错位。

---

# 1.0.5 · 下载保存位置改到系统 Download 文件夹

## 问题

工作区与预览页的「下载」把文件写进 `context.filesDir/downloads`，也就是
`/data/data/cn.cloudbuddy.app/files/downloads`。这是应用私有目录：
文件管理器里看不到（用户感知为「保存到了根目录」），卸载应用后文件也随之消失。

## 改法

新增 `data/Downloads.kt`，按系统版本走两条路径：

| 系统 | 写入方式 | 权限 |
| --- | --- | --- |
| Android 10+（API 29+） | `MediaStore.Downloads`，`RELATIVE_PATH = Download`，`IS_PENDING` 两段式提交 | 不需要 |
| Android 8/9（API 26–28） | `Environment.getExternalStoragePublicDirectory(DOWNLOAD)` 直写 + `MediaScannerConnection.scanFile` | `WRITE_EXTERNAL_STORAGE` |

要点：

- **落在 Download 根目录**，不再套应用子目录，和浏览器下载的文件在一起。
- 文件重名时由系统改名（`name (1).ext`）；写入后回读 `DISPLAY_NAME`，
  提示里显示的是**真实落盘名字**，不是请求的名字。
- 写入失败会删掉那条 `IS_PENDING` 记录，避免文件管理器里留下 0 字节的幽灵条目。
- `safeName()` 只取最后一段、去掉控制字符与开头的点，防止云端给的名字带 `../` 写出目录。
- 权限只在 API < 29 时申请；`WRITE_EXTERNAL_STORAGE` 在 Manifest 里限定 `maxSdkVersion="28"`，
  Android 10+ 不会索要这个已无意义的权限。

## 界面

`ui/components/Downloader.kt` 提供 `rememberDownloader()`，统一处理「权限 → 写入 → 回调」，
工作区列表与文件预览页都改用它。提示文案变为 `已保存到 Download/文件名`。

## 测试

`DownloadsNameTest` 6 项：路径分隔符、控制字符、开头点、空名兜底、超长截断、中文空格保留。

## 验证（1.0.5）

- `:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` 全绿，
  **50 个单测通过**（新增 `DownloadsNameTest` 6 项），lint 0 error
  （`ScopedStorage` 未告警：按版本分支，29+ 只走 MediaStore）。
- `aapt2 dump permissions` 确认 `WRITE_EXTERNAL_STORAGE` 带 `maxSdkVersion="28"`，
  Android 10 及以上不会索要该权限。
- APK：`1.0.5` / versionCode 6 / minSdk 26 / debug 签名，
  sha256 `82669a269f85c6d2a97560fd120a6f3fdb6ef0a3af95dc4c01d635feb1e7d57b`。
- 未做真机验证（无 KVM、无账号）：首次下载后请在文件管理器的 Download 里确认。

---

# 1.0.7 · 长会话打不开：历史加载超时与一次性渲染

## 现象

打开一个长对话时，界面停在「在云端沙箱里开始任务」的空态，
红色横幅写着 **「请求超时，请稍后重试」**，右上角角标 **「执行失败」**，
重试几次都一样——对话越长越打不开。

## 根因

三件事叠在一起，缺一不可：

1. **等的是错的东西。** `session/load` 是同步 RPC，服务端要等**整段历史回放完**才回响应，
   而客户端用 ACP 默认的 **30 秒**超时等它。历史一长必然超时。
2. **超时被当成失败。** `initializeAndLoad()` 抛 `SocketTimeoutException`，
   `openConversation()` 的 `onFailure` 直接把 `state` 置成 `FAILED`。
   可此时连接是活的、消息也已经通过 SSE 到了不少——全被这一下判死。
3. **一次性渲染。** 回放期间 `publishCoalesced()` 里 `if (replaying) return`，
   一条都不推，用户全程只能看转圈；回放结束又一次性把几千条消息塞进 `LazyColumn`，
   首次布局要测量全部项，长会话即便加载成功也会卡住。

## 改法

### 1. 超时阈值放宽，并且可调

`initializeAndLoad(timeoutMs)` 显式接收超时，来自 `AppSettings.historyTimeoutMs`，
默认 **180 秒**（原 30 秒），设置里可选 60 / 120 / 180 / 300 秒。
`initialize` 仍用 30 秒——它本来就快，慢的是回放。

### 2. 超时不再判失败

```kotlin
val complete = try { rpc("session/load", ..., timeoutMs); true }
               catch (_: SocketTimeoutException) { loadTimedOut = true; false }
loadDone = true
replaying = false
```

- 只有**连不上**（`open()` 失败、接入点非 200、无事件流）才算失败；
- 回放超时只是「历史可能不完整」，连接照常可用，界面显示一条**中性提示** +
  「重新载入」，而不是红色错误横幅；
- `connection()` 里 `connections[id] = conn` 提前到 `open()` 之前，回放期间快照就能连续推给界面。

### 3. 静默判定：不再死等响应

回放事件是边放边到的，`session/load` 的响应却要等最后。新增看门狗：

```
每 300ms 检查一次；SSE 上连续 1.5 秒没有新事件 → 认定历史放完，先放行界面
一条事件都没收到（旧网关 / 空会话）→ 8 秒硬上限兜底
```

`loadDone` 仍然等真正的响应，只是不再挡着界面。`prompt()` 开头 `awaitLoad()`
最多等 10 秒，避免用户抢先发出的消息插到回放中间。

### 4. 回放期只报进度，收尾一次性呈现

这一节写的是最终做法。中间我曾发过一版 1.0.6：让回放期也推快照（400ms 合并），
结果界面看到历史一条条往上冒、历史里的工具调用还在转圈——像是把当时的步骤重放了一遍。
用户明确否掉了这种做法：**加载完就应该是最新样子**，于是回退并把版本号提到 1.0.7，
避免同一个号对应两份不同内容。

现在回放期 `publishCoalesced()` 完全不推消息，只每 300ms 上报一个「已收到 N 条」的进度；
收尾时 `finishReplay()` 先把历史收敛成终态（清掉 `streaming` 标记、把残留的
`running` 工具降级为未知、`RUNNING` 状态回落到 `IDLE`），再一次性推全量快照。
观感回到「转一下 → 直接是最新样子」，而进度数字让长会话至少知道没卡死。

### 5. 按「轮」开窗渲染

`windowTimeline(messages, turns)`：先在**消息层面按轮裁剪**，再投影成折叠组——
按条裁剪会把一个步骤组切碎，按轮裁才安全。

- 默认只渲染最近 **20 轮**（10 / 20 / 50 / 全部可调）；
- 顶部一行「显示更早的 N 轮对话 · 还有 M 条未渲染」，点一下再扩一页；
- 展开时用 item key 做锚点，把原来第一条消息滚回原位，内容不会整体跳一下。

这就是「不一次性加载所有消息」：ACP 的 `session/load` 本身没有分页参数，
能控制的是**渲染**——长会话的卡顿来自一次性布局几千个 item，而不是数据本身。

### 6. 顺手修掉的 bug

- 菜单「重新加载对话」原本调 `openConversation()`，但连接还活着时它会直接复用缓存快照，
  **等于什么都没重新加载**。改为 `reloadConversation()`：先 `close()` 旧连接再重放。
- 设置里新增两项：一次渲染多少轮对话、历史加载超时。

## 验证（1.0.7）

- `:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` 全绿，
  **57 个单测通过**（`TimelineProjectionTest` 新增 5 项开窗用例：尾部窗口、步骤组不被切碎、
  0 表示全部、短会话不开窗、空白分片不计数；`AcpReducerTest` 新增 2 项回放收尾用例：
  清掉流式标记与残留 running、已完成的工具不被误改），lint 0 error。
- APK：`1.0.7` / versionCode 8 / minSdk 26 / debug 签名，
  sha256 `3fef1226a5211f5637e9d188aa1b29006a7844d7f5aa369a0983953a8fd8e7a0`。
- **未做真实长会话验证**（无 KVM、无账号）：静默窗口 1.5 秒、进度上报 300ms、
  默认 20 轮这几个值都是按事件密度估的，需要在真实长会话上复核。


# 1.0.8 · 补齐「提问/选择」交互

## 背景

云端在两种情况下会停下来等用户：

- `_codebuddy.ai/askuser`：AskUserQuestion 工具，一次可以问 1~4 个问题，支持多选；
- `session/request_permission`：执行命令前的权限确认，选项是 `allow_once` / `reject_always` 这类机器值。

客户端原来只有一个最小卡片，能力和协议差距很大。

## 三个真问题

### 1. 权限确认被整条丢弃（最严重）

`handleAsk()` 的入口有一句：

```kotlin
if (question.isBlank() && questions == null) return false
```

权限确认的载荷是 `{toolCall: {...}, options: [{optionId, name, kind}]}`——**没有 `question` 字段**，
于是直接 `return false`，事件被当成「不认识的通知」吞掉。表现是：云端在等确认、界面一片安静，
用户以为卡死了。

### 2. 多问题被砍成一个

```kotlin
(questions?.optJSONObject(0)?.optJSONArray("options"))
```

只取 `questions[0]`。AskUserQuestion 问三个问题时，界面上只出现第一个，答完云端还在等剩下两个。

### 3. 多选无法表达

`resolve(question, answer: String)` 把一串文字同时塞进 `optionId` 和 `answer`。
单选够用，多选只能拼成一坨，云端分不清用户选了两项还是选了一个长名字的选项。

## 改法

### 1. 模型承载完整提问

```kotlin
data class AskItem(question, header, options, multiSelect)
data class AskOption(label, description, value)   // value 是回传值，权限请求里是 optionId
data class AskPick(labels, values, custom)        // 单个问题的选择
data class AskAnswer(picks)                       // 按下标与 allItems 对应
```

`AskQuestion` 保留原来的 `question/options` 字段（旧载荷、REST 快照还在用），
新增 `items`，并用 `allItems` 统一成一问或多问的形状——**界面不再需要区分两种载荷**。

### 2. 解析只写一份

新增 `data/AskParsing.kt`：`parseAskItems(node)`。
ACP 事件和 REST 快照本来是同一种形状，以前各写一份（`AcpReducer.handleAsk` 与
`WorkBuddyApi.askOf`），多选支持了一边就会忘另一边。现在两边都调它。

顺带修掉权限确认：`text` 为空但有 `options` 时，用 `toolCall.title` 当正文，
再退化成「云端请求执行一个操作，需要你确认」。

### 3. 卡片重写

`AskCard` 从 40 行扩到可复用的 `AskCard + AskOptionRow`：

- **多问**：按 `1. 2. 3.` 逐条列出，每题独立选项；
- **多选**：Checkbox；单选：RadioButton；
- **单问单选**：不画控件，整行就是按钮（点一下即答，不多按一次）；
- **选项说明**：`description` 显示在标签下方（以前解析了但没渲染）；
- **其他（自行输入）**：每题都带输入框，可与选项组合；
- **提交**：多问/多选要显式提交；至少答一题即可提交，未答的在文本里写「未选择」。

### 4. 回传结构化答案

```kotlin
params.put("optionId", 第一个值)   // 兼容只读单值的旧网关
      .put("answer", 文本)         // "模型：云端默认\n缓存：要"
      .put("answers", [{question, header, selected:[...], custom}])
```

认识的网关能精确还原，不认识的多一个字段也无害。

### 5. 答完留痕

回答走 `resolveInterruption`，不进 `prompt`，云端不会回显。新增
`AcpReducer.recordAnswer(text)`：把选择作为一条用户消息写进本轮，`localEcho = true`
防止云端如果回显造成重复。这样翻记录能看到「当时选了什么」，也是排查问题的依据。

## 验证（1.0.8）

- `:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` 全绿，
  **66 个单测**（新增 `AskParsingTest` 9 项：多问多选解析、旧形状兼容、
  权限无正文仍能弹出、kind 翻译、optionId 优先、单选/多选/多问文本化、自定义、空答判定），
  lint 0 error。
- APK：`1.0.8` / versionCode 9 / minSdk 26 / debug 签名，
  sha256 `de77a437aa9e04f5ab2acd8df0cbe836d684d14ab15232b2ccb033e83cf0bb85`。
- **未做真机验证**（无 KVM、无账号）：`questions` 的具体字段名按官方 AskUserQuestion 的形状猜了一部分
  （`multiSelect` / `multiple` / `type: multi` 都做了兼容），真实载荷里如果还有别的字段名，
  在 `AskParsing.isMultiSelect` 里补一行即可。

# 1.0.9 · 折叠开关、断网不登出、去掉两处冗余 UI

## 1. 折叠：从「一次性动作」改成「持久化开关」

原来菜单里是「折叠全部中间步骤 / 展开全部中间步骤」，点一次只作用于**当时已有的轮次**。
用户接着发一条消息，新产生的步骤又按默认规则展开，得再点一次——等于每轮都要手动收一次。

现在改成开关（菜单里是 `Switch`，设置页也有一份），语义变成**全局意图**：

```kotlin
// AppUiState
val stepsCollapsed: Boolean = true          // 跟随 AppSettings.collapseSteps，持久化
val stepsExpanded: Map<Int, Boolean>        // 只记手动覆盖，且只在本次会话有效

// 渲染
val expanded = ui.stepsExpanded[turn] ?: !ui.stepsCollapsed
```

- 开启 → 已有轮次全部折叠，**新轮次也没有覆盖记录，天然折叠**；
- 关闭 → 全部展开，新轮次同样跟随；
- 单独点某一轮的摘要行 → 只影响那一轮，不写回偏好；
- 切换开关时清空 `stepsExpanded`，否则旧的展开记录还在，看着像开关没生效。

`setAllStepsExpanded()` 随之删除。

## 2. 断网启动被踢回登录页

### 根因：两次「失败」被压成了同一个 false

```kotlin
val ok = runCatching { api.probe() }.getOrDefault(false)   // probe 内部也是 runCatching{}.isSuccess
if (!ok) {
    val renewed = login.renew()                            // renew 内部同样 runCatching{}.getOrDefault(false)
    if (!renewed) { 登出 }
}
```

三个环节都把异常吞成 `false`，于是「密码/凭据无效」和「网线断了」在启动流程里长得一模一样。
网络一差 → probe 失败 → renew 也超时 → 判定登录失效 → 回到登录页，还得重新等短信验证码。

`CloudHttp` 那边还有一层放大：401 时会调 `renewer`（`runBlocking { login.renew() }`），
renew 又被吞成 false，最终抛 `SessionExpiredException` —— 一次超时彻底变成「登录已过期」。

### 改法：三态探测 + 只有明确失效才登出

```kotlin
enum class SessionProbe { VALID, INVALID, UNREACHABLE }

internal fun sessionProbeOf(error: Throwable?): SessionProbe = when {
    error == null -> VALID
    error is SessionExpiredException -> INVALID            // 网关明确说凭据不可用
    error is CloudException && error.code in setOf(401, 403) -> INVALID
    else -> UNREACHABLE                                     // 超时 / DNS / 断网 / 5xx
}
```

启动流程按状态分支：

| 探测结果 | 处理 |
|---|---|
| `VALID` | 正常进主页，刷新五个接口 |
| `INVALID` | 尝试静默续期；续期返回 `false` 才登出；续期**抛异常**（网络问题）按离线处理 |
| `UNREACHABLE` | 直接进主页，不发任何请求、不弹错误 |

配套改动：

- `probeState()` 用 `allowRenew = false` 探测——探测本身不该触发续期，那要连发几次请求，网络差时会把启动拖到超时；
- `LoginClient.renew()` 不再吞异常：网络失败会抛出，调用方才能区分「该登出」和「先离线用着」；
- `bootstrapConsoleSession()` 去掉 `runCatching`，同理；
- `http.renewer` 外面包一层 `runCatching`，自动续期遇到网络异常仍按「续不上」处理，不改变运行中 401 的行为；
- `AppUiState.offline` 标记，会话页空态从「还没有会话」改成「当前网络不可用 · 登录状态还在，联网后下拉刷新」，避免用户以为会话丢了；
- `refreshConversations()` 成功时清掉 `offline`。

## 3. 去掉两处冗余 UI

- **输入框下方的小字**「任务在云端沙箱执行 · 附件会先写入沙箱再交给 agent」——每屏都占一行，信息价值低；
- **对话页顶部的「云端产物 · N」栏**——与「工作区」功能重复，且挤占阅读空间。产物链接仍然会被识别并挂到会话上，只是统一去工作区看，`ChatScreen` 的 `onOpenArtifact` 参数一并移除。

## 验证（1.0.9）

- `:app:assembleDebug` + `:app:testDebugUnitTest` + `:app:lintDebug` 全绿，
  **71 个单测**（新增 `SessionProbeTest` 5 项：无异常、会话过期、401/403、超时/DNS/断网、5xx），
  lint 0 error。
- APK：`1.0.9` / versionCode 10 / minSdk 26 / debug 签名，
  sha256 `4549a675c8aa952480b642e76a54602ce0f7f069cd3ea715fbde6e0c543b4623`。
- **未做真机验证**（无 KVM、无账号）：断网启动这条只能靠代码路径保证——
  要真机复核的是「飞行模式下点开 App 是否直接进主页且不弹错误」。

# 1.0.10 · 提问卡片根本不弹：接上服务端反向请求

## 现象

1.0.8 号称补齐了「提问/选择」，真机上试（1.0.9）——**卡片一次都没弹过**。
云端那边看起来停住了，界面一片安静，只能点停止。

## 根因：三条通道只占了半条

1.0.8 只处理了「服务端发**通知**」这一种形态：

```kotlin
"session/request_permission", "session/requestPermission", "_codebuddy.ai/askuser" -> handleAsk(params)
```

但真实的提问有两种主流形态，都不在这个 `when` 里：

### 1. 服务端**反向请求**（JSON-RPC request，带 `id`）

官方 Web 端 `createClientHandler()` 的原文：

```js
extMethod: async (t, n) =>
  t === "_codebuddy.ai/question" ? this.waitForQuestion(n) : {}
```

也就是说，服务端是**调用客户端方法** `_codebuddy.ai/question`，params 形如
`{toolCallId, schema:{questions:[{id, header, question, options, multiSelect}]}, timeout}`，
客户端必须用**同一个 `id`** 回一个 `result`：`{outcome:{outcome:"submitted",data:{answers:{...}}}}`。
不回包，服务端就一直挂着（官方客户端等 5 分钟：`sxn = 5*6e4`，到点回 `cancelled/reason:"timeout"`）。

而我们的 `receive()` 里根本没有「带 `method` 和 `id` 的帧」这条分支：

```kotlin
val id = root.optInt("id", -1)
if (!root.has("method")) { /* 只当响应处理 */ }
// 有 method 的一律丢给 reducer.accept()，未知方法直接 return false → 不回包
```

于是这条请求被当成「不认识的通知」吞掉，服务端等满 5 分钟。用户看到的就是「没反应」。

### 2. `AskUserQuestion` 走的是**工具调用**

官方判断方式是按工具名匹配，并把 `-` `_` 空格去掉：

```js
isAskUserQuestionToolName(t){ return t.replace(/[-_\s]/g,"").toLowerCase()==="askuserquestion" }
isPendingAskUserQuestionStatus(t){ return ["idle","pending","parsing","stream_executing",
  "full_executing","executing"].includes(t) }
```

问题体在 `tool.args.questions`（可能是数组，也可能是 JSON 字符串）。
我们的 reducer 只把它当普通工具，在折叠的步骤组里画一条转圈的「Ask User」，
卡片自然永远不弹——1.0.9 又把步骤默认折叠了，连通圈的都看不见。

### 3. 回传格式也不对

1.0.8 回的是自造的数组：

```kotlin
.put("optionId", …).put("answer", text).put("answers", [{question, header, selected, custom}])
```

官方是 `{问题id: 单值或数组}`，并且明确区分单选多选：

```js
r[o.id] = s.multiSelect ? [...o.answers] : o.answers[0]
```

## 改法

### 1. 接住反向请求并按 id 回包

`AcpConnection.receive()` 先分流：

```kotlin
if (root.has("method") && root.has("id")) { onServerRequest(root); return }
```

`onServerRequest()`：`_codebuddy.ai/question` / `session/request_permission` 解析成提问、
记下 `toolCallId → (rpcId, 是否权限)`，交给 reducer 弹卡片；**未知方法也回一个空 result**，
宁可让对面早点报错，也不要静默等 5 分钟。

回包必须换线程（`replyExecutor`）：SSE 读线程在 `receive()` 里同步 POST 会自己等自己。
回包失败用 `fatal = false`，只让这一问超时，不牵连连路上的实时流。

### 2. 工具调用形态也认

`AcpReducer` 的 `tool_call` 分支先按名字判断，pending 状态且 `rawInput.questions` 非空就
`showAsk()` 并**不再画工具行**（卡片已经代表这次交互）；状态变成 `executed` / `completed`
时把卡片收起，避免挂着一个过期问题。

### 3. 回答按协议回

- 来自反向请求 → 原 `id` 回 `{outcome:{outcome:"submitted",data:{answers}}}`
  （权限确认回 `{outcome:{outcome:"selected",optionId}}`）；
- 来自工具调用 → `_codebuddy.ai/resolveInterruption`，带 `decision:"allow"` 与 `answers`。

`answers` 由 `buildAnswers()` 生成：单选给字符串、多选给数组、没选但自己填了字就用文字；
选项一个都没选则不写这个 key（不要凭空塞一个假答案）。

### 4. 停止时把提问回成 cancelled

`cancelTurn()` 先 `cancelPendingAsk()`，否则用户点了停止，服务端还在等那一问。

## 验证（1.0.10）

- `scripts/verify.sh` 全绿：**80 个单测**（新增 9 项：`schema` 解包与 toolCallId、
  `answers` 单选给字符串/多选给数组、自定义文本填 answers、空选不写 key、
  工具名宽松匹配、无问题载荷拒绝弹卡、工具调用弹卡、答完收卡、回放期不弹卡），lint 0 error。
- APK：`1.0.10` / versionCode 11 / minSdk 26 / debug 签名，
  sha256 `f18f4f6a16ad8c02eb8ff65c1fab98a87611f3f14af4aabc72cd8ee4f524567a`。
- **仍未做真机验证**：`_codebuddy.ai/question` 与工具调用哪条会先到、`answers` 的 key
  用的是问题 `id` 还是序号，都只能按官方 Web 端的实现推断。1.0.10 装上后再发一次提问卡片即可确认。

> 后续补记：1.0.10 已在真实设备上测过，卡片能弹、单选回字符串、多选回数组且保序、
> 未作答的题不出现在 `answers` 里，服务端接受后能继续跑。实测口径见 AGENTS.md。

---

# 1.1.0 · 关于板块、检查更新、正式签名与首个 Release

## 1. 关于板块加两个入口

`SettingsScreen` 的「关于」卡片原本只有版本号和三段说明，现在补两行可点条目：

- **GitHub 仓库** → `https://github.com/silverwolf-lv9999/CloudBuddy`
- **检查更新** → 读 GitHub Releases 比对版本

跳转统一走 `openUrl()`：`ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK`，没有浏览器时 Toast 提示，
不静默失败（静默失败最常见的原因是用户点了没反应，然后以为功能坏了）。

## 2. 检查更新：把「网络失败」和「已是最新」分开

新增 `data/UpdateChecker.kt`，只碰一个外部地址：`api.github.com/repos/<slug>/releases/latest`。
独立 OkHttpClient，连接 8 秒 / 读 12 秒——这条请求不该拖住设置页。

界面状态是五态而不是布尔：

```
Idle → Checking → UpToDate | Available(info) | Failed(msg)
```

**`Failed` 必须单独存在**。把网络不通显示成「已是最新」等于骗用户说不用升级，
而国内网络下 `api.github.com` 恰恰经常不通。这是本轮唯一一个「看起来能用但会误导」的坑。

版本比较写成纯函数 `compare(latest, current)`：逐段按数字比，缺位按 0 补齐。
字符串比较会得出 `1.0.9 > 1.0.10`，这是必须避开的经典错误，单测里专门钉了一条。

`parseRelease()` 对异常载荷一律返回 null（空体、`{}`、502 的 HTML），不抛异常：
GitHub 偶尔返回错误页，解析崩了会让整个设置页挂掉。

## 3. 正式签名

- `keytool` 生成 4096 位 RSA，别名 `cloudbuddy`，有效期 10000 天（到 2054-04-11）；
- 密钥落在 `keystore/cloudbuddy-release.jks`，被 `.gitignore` 排除；
- 凭据四项写进 `local.properties`（同样不入库），`app/build.gradle.kts` 读它；
- 缺凭据时 release 构建出未签名包，`verify.sh` 会拒收——故意的。

`scripts/verify.sh` 同时改了两处：

1. 默认出 **release** 包（`--debug` 才回退），归档名改 `CloudBuddy-<版本>-release.apk`；
2. 正式包额外比对证书 DN。`apksigner verify` 只验签名自洽，**debug key 签的包照样能过**，
   所以脚本显式拒收 `CN=Android Debug`，并要求 `CN=CloudBuddy`。

## 4. 发布

首个 Release `v1.1.0`，APK 作为资产上传，说明里给出 sha256 与安装方式。

## 验证（1.1.0）

- `bash scripts/verify.sh` 全绿：**88 个单测**（新增 8 项：release 解析去 `v` 前缀、
  无 apk 资产回退、异常载荷返回 null、版本号逐段按数字比、相同版本不算更新、
  缺位补零、预发布低于正式版、`html_url` 缺失回退），lint 0 error。
- APK：`dist/CloudBuddy-1.1.0-release.apk`，versionCode 12，minSdk 26，
  4096 位 RSA 正式签名，sha256 `d6e91b11d18cdc0b05d28cd8b960868b524a5b5038b6162981f05d073c3fd96c`。
- 证书 SHA-256：`59:29:B6:F4:7F:2B:C4:6F:B9:F5:4B:2F:15:BD:42:C4:37:55:81:76:06:38:C0:66:49:93:B8:4A:2B:46:8F:90`。
- **未做真机验证**：检查更新的界面流转（点击 → 检查中 → 发现新版 → 对话框 → 跳浏览器）
  只经过代码路径与单测，没有在设备上点过。已知风险是 `api.github.com` 在国内网络下不通时
  会落到 `Failed` 分支，文案已经按「检查失败」写。

# 1.3.0 · 账号里的 MCP 连接器不是「云端无接口」

## 背景
用户指出：在官方安卓端设置过 GitHub 的 MCP，网页端也能用，说明 MCP 跟随账号。
1.2.0 的结论是「MCP 没有云端管理接口，只有本机配置」，据此写出来的 MCP 卡片
只能粘贴 JSON、随 `session/new` 下发，看不到用户账号里真实存在的 GitHub 连接器。

## 根因
把两种东西混成了一类：

- 市场连接器（GitHub / Figma 等）存在云端账号里，官方 App / web / 安卓端共用一条记录；
- 自定义 MCP（`custom-mcp:` 前缀）才是本地 agent 侧配置，走 `getMcpServers/toggleMcpServer`
  广播，没有 REST。

1.2.0 只看到了后者的实现，就把结论推广到了全部 MCP。判断依据是 web 产物
`main-*.js` 里的 `getMcpServers` 走 agent provider，但同一个文件里还有
`CloudConnectorRepo`，`GET /console/as/connector/user/` 才是账号清单。

## 改法
新增 `data/ConnectorApi.kt`，把连接器接口按 web 产物的常量还原：
`user/` 清单、`registry2c/list?scope=all&mcp_only=true` 市场、
`PATCH .../active_status` 启停、`DELETE .../user/{name}/` 解绑、
`oauth/{name}/start|status|connect|revoke` 授权链路。
`mine()` 除了读 `connect_status` / `active_status`，还会查一次 `oauth/auth-status`：
前者只说明「绑过」，token 过期后仍是 1，三者都满足才算可用。

设置页拆成两张卡：`ConnectorCard`（账号级，云端同步）和原来的 `McpCard`
（本机自定义，只随会话下发）。切账号时清空连接器状态，避免上一个账号的 GitHub
显示在新账号下。市场条目里的 `mcp.sandbox_url` 是沙箱内网关地址，不下发给 ACP。

## 验证（1.3.0）
- 真账号实测（用户提供短信验证码，全程在沙箱内存里完成，凭据没有入库）：
  - `GET /console/as/connector/user/` → `data.connectors[]` 里有用户的 `github`，
    `connect_status=1`、`active_status=1`、`connect_at=2026-09-27T10:39:36+08:00`；
  - `POST /console/as/connector/oauth/auth-status {names:["github"]}` → `statuses.github.status=connected`；
  - `POST /console/as/connector/oauth/github/start` → `next_action=connected`（已连着时直接返回）；
  - `GET registry2c/list?scope=all&mcp_only=true` → 61 条，github 项的 `data.display_name`
    是「GitHub MCP」，带 `mcp.gateway_url` / `mcp.sandbox_url`；
  - `PATCH .../user/github/active_status` 置 0 再置 1，两次都 200；`get-list` 复查
    先变 `active_status=0`，恢复到 `1`，与测试前一致。
- `bash scripts/verify.sh` 全绿：102 个单测（新增 5 项连接器解析用例），lint 0 error。
- APK：`dist/CloudBuddy-1.3.0-release.apk`，versionCode 14，正式证书，
  sha256 `d47b39c4868dcbb436dc09624940b209105a5dc3f27770e7d11c32d5d2b4da09`。
- **未做真机验证**：设置页新增的连接器卡片没有在设备上点过；授权对话框的三种
  `next_action` 分支只覆盖了 `connected` 和 `redirect` 的解析单测，`show_form` / `poll`
  的真实返回形状还没有抓到过。另一个待确认点是：账号里的连接器由沙箱侧按账号解析
  （市场条目的 `mcp.sandbox_url` 指向沙箱内网关），客户端不下发 `mcpServers` 是否就已生效，
  需要在真机开一个会话问 Agent「你有哪些工具」来确认。

# 1.3.1 · 技能市场加载失败：knot user identity unavailable

## 背景
1.3.0 真机实测：已安装技能能正常读取，点「浏览技能市场」弹
`技能市场：knot user identity unavailable`。

## 根因
1.2.0 挑市场路径时选了 web 产物里的 `knot` 来源：
`GET /console/agent-gateway/internal_knot-skill/apigw/api/v1/skills/list`。
真账号抓包确认它对当前用户回 `403 {"code":17012,"msg":"knot user identity unavailable"}`，
补 `X-User-Id`、`X-WB-UA` 都无效——这个账号在该网关没有身份，不是请求头拼错。
同一份 web 产物里默认用的是企业聚合市场，knot 只是其中一个可切换来源。

## 改法
`SkillApi.market()` 改走
`POST /portal/enterprises/{enterpriseId}/skill-market/list?source=builtin&page=1&page_size=20`，
个人账号 `enterpriseId` 就是字面量 `personal`。两个容易踩的点：参数全在 query，
**body 必须为空**（带 body 回 400 `invalid request body`）；搜索词是 `keyword`
而不是 knot 那边的 `search_keyword`。响应在 `data.items[]`，id 用 `skill_id`，
标题用 `display_name_zh`，安装仍走 `/v2/user-asset/skill/install` 的 `source=market`。

## 验证（1.3.1）
- 真账号抓包：企业聚合接口 200，`total=70656`，第一条就是「AI交付前全自动自检技能」；
  `keyword=去AI味` 返回 99 条，首条正是「去AI味」v1.2.1 之前的 v1.2.0 记录。
- `POST /portal/operation-platform/market/skill/list` 返回空列表，确认不能当兜底。
- `bash scripts/verify.sh` 全绿：105 个单测（新增 3 项市场解析用例），lint 0 error。
- APK：`dist/CloudBuddy-1.3.1-release.apk`，versionCode 15，正式证书，
  sha256 `e13a4ca9ee9849f67f543f9cee3a4555d89d016cc2809aae62f5f578cae16aea`。
- **未做真机验证**：新接口没有在用户设备上点过；企业账号（非 `personal`）的
  `enterpriseId` 走同一路径是否可用，也只对个人账号验证过。

# 1.3.2 · 技能市场：method POST must have a request body

## 背景
1.3.1 真机实测：MCP 连接器正常，技能市场仍报错 `技能市场：method POST must have a request body.`。

## 根因
错误信息是 OkHttp 客户端本地抛的，不是服务端：`Request.Builder.method("POST", null)`
对 POST/PUT/PATCH 强制要求 RequestBody，body 为 null 时直接抛 `IllegalArgumentException`，
请求根本没发出去。1.3.1 把市场接口改成「POST + query、不传 body」后，Python requests
抓包时会自动补 `Content-Length: 0`，返回 200，因此没暴露 Kotlin 侧的差异。

## 改法
`CloudHttp.bodyFor()` 统一处理：body 为 null 且方法属于 POST/PUT/PATCH 时，补一个
长度为 0 的 JSON RequestBody；GET/DELETE 仍保持 null。`SkillApi.market()` 不需要
改接口路径，也不需要构造空 JSONObject（带字段的 body 服务端会回 400）。

## 验证（1.3.2）
- 4 项新增单测：POST/PUT/PATCH 补空 body、GET/DELETE 保持 null、有 body 时按 JSON 序列化。
- `bash scripts/verify.sh` 全绿：109 个单测，lint 0 error，正式签名。
- **未做真机验证**：需要在用户设备上再点一次「浏览技能市场」确认弹窗消失。

# 1.3.3 · 三个真机问题：回放 / 误判超时 / 输入栏串会话

## 背景
用户真机反馈：①冷启动首个会话先加载再显示正常，但退出再点另一个会话又变成逐条回放，
要求所有会话都加载完再显示；②云端任务还在跑（"正在 Get Task Output" 转圈），客户端
先弹「请求超时，请稍后重试」并标执行失败；③A 会话任务在跑时切到已完成的 B 会话，
输入栏仍显示 A 的红色暂停按钮。

## 根因与改法
1. **回放**：`watchReplay` 用「事件静默 1.5s」提前 `endReplay()`，之后到达的历史事件
   在 `replaying=false` 下被 `publishCoalesced` 逐条推上屏。改为：`publishCoalesced` 的
   闸门用 `!loadDone`（只认 `session/load` 收尾），看门狗静默只上报「载入较慢」提示，
   不再结束回放。
2. **误判超时**：`postRpc` 同步读 SSE 到流结束，等待时长实际由 OkHttp `call.timeout()`
   （窗口+30s=10.5 分钟）控制，`latch.await` 走不到。10 分钟后客户端掐断仍在推进度的
   任务流并抛 `SocketTimeoutException`，`send()` catch 转成 FAILED。改为：`rpc()` 加
   `WaitKind`，prompt 的 call timeout 用 2 小时兜底；超时抛新的 `PromptWaitTimeout`，
   `send()`/`answerQuestion()` 捕获后只发事件提示、不标失败、不弹红色错误。
3. **串会话**：`ui.sending` 全局布尔，A 发送后 coroutine 阻塞期间切到 B，`sending`
   仍为 true。改为：`openConversation`/`startNew`/`closeConversation` 清 `sending`；
   发送协程锁定 `targetId`，新增 `applyIfActive(id)`，状态与错误只写回仍活跃的原会话。

## 验证（1.3.3）
- `bash scripts/verify.sh` 全绿：112 个单测（新增 `LoadGateTest` 3 项），lint 0 error。
- APK：`dist/CloudBuddy-1.3.3-release.apk`，versionCode 17，正式证书，
  sha256 `34b2e7ad6c482e15c2cdb8ad8cdc6dc8f8adab37713593083cc024db53d7d8f7`。
- **未做真机验证**：三个问题都需在用户设备上复测。超时修复只把窗口从 10 分钟放宽到
  2 小时并改成软超时；超过 2 小时的任务仍需观察 reducer 是否能靠 SSE 收敛终态。

# 1.3.4 · 状态机整体与云端脱钩：假运行 / 假停止 / 跑着还能发消息

## 现象
用户真机反馈三种错位，且官方客户端也有类似问题：
1. 任务其实做完了，会话列表还显示「进行中」；
2. 云端任务仍在继续（网页端能看到新输出），客户端显示已停止、也不刷新新内容；
3. 云端实际在跑，客户端却显示空闲并允许发送，消息插进正在执行的轮次，上下文被弄乱。

## 根因
不是某个分支写错，而是**架构上缺一条对账通道**：
- ACP 每条 RPC（`session/load`、`session/prompt`）都是一条独立的短命 SSE 流，
  `postRpc` 同步读到流结束才返回。响应一处理完，流就没有了——两条 RPC 之间客户端收不到
  任何事件。任务在网页端 / 另一台设备 / 云端继续跑，本地 reducer 完全不知情。
- 于是本地状态只能靠「上次自己看到的事件」推断：漏了收尾就永远 RUNNING（假进行中）；
  重放收尾 `finishReplay()` 又会把 RUNNING 压成 IDLE（假停止）；两者都直接决定输入栏和列表角标。
- `send()` 以前只判断输入框有没有文字，不看云端是否真的空闲。

## 改法
1. 新增 `data/StateReconciler.kt`（纯函数、可单测）：本地有流在推时以本地为准，否则采纳
   服务端记录；「有任务 ↔ 没任务」的跨越额外触发一次历史补拉。
2. 新增 `WorkBuddyApi.batchGet`（官方 `POST /console/as/conversations/batch-get {ids}`），
   对账只查当前会话一个 id。
3. `BuddyRepository` 增加对账循环 `stateSyncLoop`：打开会话后先对一次（`reconcileAfterOpen`），
   之后按运行态调频；采纳云端 RUNNING 时输入栏自动变暂停，从而挡住发送。
4. `silentResync`：重建连接、重放历史、静默替换消息（不显示载入条、不清空），用于把
   云端在别处跑出来的新输出补回本地；按 `updatedAt` 去重 + 8s→60s 退避。
5. `send()` 双重闸门：本地已知 RUNNING/WAITING 直接拒发；发送前再用 `batch-get` 确认一次
   空闲（`confirmIdle`），本地状态过期时拒发并提示。
6. `stop()` 支持「任务在别处发起」的情况：没有活连接时临时建一条只下发 `session/cancel`，
   用完立刻关掉（不缓存，避免被复用成空历史）。

## 性能约束（用户要求不能卡）
- 后台完全停轮询（`MainActivity.onPause/onResume` → `setForeground`），回前台立刻补一次；
- 间隔分档：云端在跑 10s / 本地推流 30s / 空闲 45s；列表全量刷新 60s 一次（只用来更新角标）；
- 补历史是唯一的重操作：`updatedAt` 没前进就不补，两次之间退避到 60s，本地发送中不补；
- UI 写入加锁且只在字段真有差异时进行，避免把 SSE 刚推上来的消息覆盖回旧快照；
- 列表比较只比 `id/title/state/updatedAt/preview`，不用 data class 的深度 equals。

## 验证（1.3.4）
- `bash scripts/verify.sh` 全绿：119 个单测（新增 `StateReconcilerTest` 7 项），lint 0 error。
- APK：`dist/CloudBuddy-1.3.4-release.apk`，versionCode 18，正式证书，
  sha256 `7577871c6dfd6c87015075d9a469c848284ea83ed31e1bad97d438540dbaf28d`。
- 真账号接口只验证过 `batch-get` 的路径与响应形状（web 产物里的官方实现），**未做真机复测**：
  ①跑完任务的角标收敛、②网页端继续跑时手机端能否跟上并补出新输出、
  ③云端在跑时发送是否被拦下，三项都需要在设备上确认。
