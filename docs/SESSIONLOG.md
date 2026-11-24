# 执行日志

按时间正序记录每步动作与结论，供上下文压缩后恢复现场。凭据与验证码一律不记。

## 2026-11-24 · 技能市场 POST body（1.3.2）

- **10:20** 用户反馈：1.3.1 装上后「MCP 是正常的，技能市场还是打不开」，截图报错 `技能市场：method POST must have a request body.`
- **10:21** 读 `CloudHttp.kt:execute()`：`body?.toString()?.toRequestBody(JSON)`，body 为 null 时把 null 传给 `Request.Builder.method("POST", null)`。OkHttp 对 POST 强制要求 RequestBody，本地直接抛 `IllegalArgumentException`，错误文本与截图逐字一致 → 确认是客户端异常，不是服务端 400。
- **10:21** 读 `SkillApi.kt:market()`：1.3.1 改成 `POST /portal/enterprises/personal/skill-market/list`，参数在 query、body 不传。此前用 Python `requests` 抓包（自动带 `Content-Length: 0`）返回 200，掩盖了 OkHttp 的差异。
- **10:22** 对照实验：未登录下 `POST` 无 body / 空 body / `{}` 三种形态均 401，说明服务端先鉴权，不挑 body 形态；空 body 可用。
- **10:22** 决定：在 `CloudHttp` 统一给 POST/PUT/PATCH 的 null body 补空 JSON body，不改接口路径。
- **10:23** 改 `CloudHttp.kt`：新增 `bodyFor()`，POST/PUT/PATCH 的 null body 补空 JSON RequestBody；`execute()` 改用它。
- **10:24** 改 `SkillApi.kt` 注释，明确不传 body 由 HTTP 层补；未改接口路径。
- **10:25** 新增 `CloudHttpBodyTest.kt`（4 项），单测通过（109 项）。
- **10:26** 版本升 1.3.2 / versionCode 16，同步 README / AGENTS.md / FIXLOG.md。
- **10:29** `verify.sh` 全绿：109 单测 / lint 0 error / 正式签名；产物 `dist/CloudBuddy-1.3.2-release.apk`，sha256 `441cf86e390a20ffd116b2ac41816aa65be11ef4c9ff4835f81b9a69ba8280f7`。
- **10:30** 提交 `287dd11`，push main 首次 502、第二次成功；tag `v1.3.2` 已推送，等待 CI 自动发版。
## 2026-11-24 · 应用商店示意图

- **10:40** 用户确认 1.3.2 真机没问题，要求做 4 张应用商店示意图。
- **10:41** 读 `docs/ui-wireframe.png` 与 `Theme.kt`：底部三栏（会话/工作区/设置），深色主题 primary `#9EC0FF`、surface `#141A23`、background `#0D1117`。
- **10:42** 定 4 个卖点：①云端会话 ②智能对话（折叠+正文+产物）③沙箱工作区 ④技能与 MCP。
- **10:43** 用 HTML/CSS 画 1080x1920 宣传图（手机外框 + 文案），生成脚本在 artifact 目录 `store-shots/gen.py`。
- **10:44** Chromium headless 截图；发现视口比窗口少 88px、底部白边切掉副标题 → 窗口高度设 2008，裁回 1920。
- **10:45** 4 张 PNG 全部复核通过，输出 `promotion/01-sessions.png` ~ `04-skills.png`。

## 2026-11-24 · 三个真机问题（会话回放 / 客户端超时 / 输入栏串会话）

- **11:20** 用户报 3 个问题：①冷启动首个会话先加载再显示，但切到另一会话又变回放，要求全部加载完再显示；②云端任务还在跑（"正在 Get Task Output"转圈），客户端弹"请求超时，请稍重试"并标执行失败；③空闲会话的输入栏却显示另一个运行中会话的红色暂停按钮。附两张真机截图。
- **11:21** 开始定位：需在 ChatScreen / BuddyRepository / AcpConnection 三处分别找回放、超时、输入栏状态来源。
- **11:25** 根因确认：①看门狗 1.5s 静默提前 endReplay，闸门从 replaying 改成 !loadDone；②prompt 的等待实际由 OkHttp call timeout 控制（同步读 SSE），10.5 分钟掐断任务流，改用 2h 兜底 + PromptWaitTimeout 软超时；③ui.sending 全局，切会话未清 + 后台错误污染，加 applyIfActive(id) 锁定会话。
- **11:32** 改 AcpConnection.kt（watchReplay/publishCoalesced/rpc/postRpc/prompt）与 BuddyRepository.kt（send/answerQuestion/stop/三个会话切换入口）。
- **11:38** 新增 LoadGateTest（3 项），版本升 1.3.3 / versionCode 17，verify.sh 全绿：112 单测、lint 0、正式签名、sha256 34b2e7ad...d8f7。

## 2026-11-24 · 状态机全面混乱（列表假运行 / 客户端假停止 / 云端跑着却可发消息）

- **11:45** 用户反馈：任务做完列表仍显示进行中；云端还在跑（网页端能看到新输出）客户端却显示已停止、不刷新；云端实际在跑时客户端还能发消息打乱上下文。核心是 turn 状态与云端真实状态脱钩，需要系统性对齐而不是补丁。
- **11:52** 定位：ACP 每条 RPC 是独立短命 SSE，RPC 之间无推送；本地状态纯靠推断 → 三类错位。
- **11:58** 新增 `WorkBuddyApi.batchGet`（官方 batch-get）、`data/StateReconciler.kt`（纯策略 + 单测）。
- **12:05** `BuddyRepository` 加 `stateSyncLoop`/`reconcile`/`reconcileAfterOpen`/`silentResync`/`confirmIdle`；`send` 双重闸门；`stop` 支持无连接时临时下发 cancel；`MainActivity` 前后台切换驱动对账。
- **12:12** 性能收敛：后台停轮询、10s/30s/45s 分档、列表 60s、补历史按 updatedAt 去重并退避、UI 写锁 + 增量写入、列表浅比较。
- **12:18** 版本升 1.3.4 / versionCode 18，verify.sh 全绿：119 单测、lint 0、正式签名，sha256 7577871c...f28d。
- **12:26** 发现全局 `sending` 不可靠（切会话被清、切回不置位），改为按会话 `inFlightTurns` 跟踪在途轮次；`send`/`answerQuestion` 登记，`finally` 按真实 id 注销，`stop` 清理。
- **12:30** 版本升 1.3.5 / versionCode 19，verify.sh 全绿：119 单测、lint 0、正式签名，sha256 647c9341...f510。


## 2026-11-24 · 用真账号核对「Clone CloudBuddy repository locally」的状态

- **15:05** 用户要求用已登录账号看这个会话的真实状态。短信重新登录（验证码不记），`probe_connector.py send/verify` 走通，cookie 落 `/tmp`，随后清掉。
- **15:09** `GET /console/as/conversations/?type=cloud&page=1&size=40` 列出 17 条会话，字段名是 `name`（不是 `title`），状态取 `status` / `sessionStatus`，时间是 `updatedAt`。
- **15:10** `POST /console/as/conversations/batch-get {ids}` 取单条：目标会话 `status=completed`、`sessionStatus=completed`、`updatedAt=2026-11-24T14:45:53+08:00`、`createdAt=13:12:36`、`agentDirty=false`。
- **15:11** 关键发现：`lastActivityAt` 在**所有**会话上都等于 `createdAt`（包括正在跑的当前会话），是死字段；`currentVersionId` 恒为 0；`agentDirty=true` 会残留在旧会话上（`agentDirtyAt` 都停在 11:02:00）。→ 只有 `sessionStatus`/`status` + `updatedAt` 可信，客户端正好只用这两个（`WorkBuddyApi.parseConversation` 注释已写明 `sessionStatus` 优先）。
- **15:12** 子资源（`/conversations/{id}/session`、`/messages`、`/versions`）对这份登录态一律 401 `invalid_token`，而 `batch-get`/列表 200；`GET {link}/acp` 也报 `missing bearer token`。→ 沙箱侧拿不到 ACP token，读不到消息正文，只能读 console 记录的元数据。
- **15:13** 结论：该会话云端早已 `completed`（跑了约 1 小时 33 分后结束），现在没有任何任务在跑；「列表一直转圈 / 界面一直显示进行中」如果仍出现，只能是本地状态没被对账覆盖，不是云端滞后。

## 2026-11-24 · 「假运行」锁死（1.3.6）

- **15:22** 用户补充：这个会话在客户端一直显示运行中、发不出消息；点「结束」后能发，但一发就提示云端在运行。与云端 `completed` 直接矛盾 → 转为查本地状态机。
- **15:24** 定位 `send()` 第一道闸 `isActive(state)` 与提示语；确认用户撞的是**本地**状态残留，不是云端。
- **15:26** 读 `AcpConnection.cancelTurn()`：只发 `session/cancel` + `markStopped()`，**没有打断阻塞中的 `call.execute()`**（prompt 的 SSE 同步读）。而 `inFlightTurns` 要等 `prompt()` 返回才注销，`decide()` 又「本地在途优先」→ 状态永久锁在 RUNNING，直到 2 小时 callTimeout。
- **15:30** 改 `AcpConnection`：加 `promptCall`/`cancelRequested`/`TurnCancelled`/`isStreamActive`；`cancelTurn()` 先打断本地流再异步通知云端；`postRpc` 对用户停止静默收敛（不关连接、不报错）；`close()` 也按主动停止处理。
- **15:34** 改 `BuddyRepository`：新增 `hasLiveTurn(id)`（登记 + 90s 内 SSE 有事件），对账/补历史/发送闸门全部改用它；提示语改为「上一轮还没收尾」。
- **15:36** 编译通过；版本升 1.3.6 / versionCode 20，`verify.sh` 后台开跑。
- **15:38** `verify.sh` 全绿：119 单测、lint 0、正式签名；产物 `dist/CloudBuddy-1.3.6-release.apk`，sha256 `f28d0e62...3268`。
- **15:40** 提交 `85a983e`，push main 与 tag `v1.3.6` 均成功，CI 自动发版。

## 2026-11-24 · 重装仍转圈（1.3.7）

- **15:42** 用户补充：重装 1.3.5 后该会话仍显示运行中、发不了消息；网页端正常，且在网页端发了一条消息后会话恢复。
- **15:44** 重新短信登录复核 `batch-get`：该会话 `completed`，`updatedAt` 从 14:45:53 跳到 15:25:35（用户网页端那一发）。凭据已删。
- **15:46** 确认 1.3.6 修不了重装场景（内存态已清空），且 `finishReplay()` 会把历史 RUNNING 收敛成 IDLE → 状态只能来自 SSE 滞留的 working 事件。
- **15:50** 改 `AcpReducer.allowRemoteRunning` + `AcpConnection.setLocalTurnActive()` + Repository 发送/回答的登记与清理；新增单测。
- **15:53** `verify.sh` 全绿：120 单测、lint 0、正式签名；`dist/CloudBuddy-1.3.7-release.apk`，sha256 `ececd0a6...f310`。

## 2026-11-24 · 抄 rikkahub 的消息渲染（进行中）

- **16:02** 用户要求优化对话界面：思考内容能点击展开/收回、表格能渲染，明确「直接抄 rikkahub」「展开收回方式也抄过来，最好整个都抄」。
- **16:03** 克隆 rikkahub 到 artifact 目录，通读关键实现：
  - `ui/table/DataTable.kt`：SubcomposeLayout 两阶段测量、横向滚动、行内等高、列宽 min/max、对齐覆盖；
  - `ui/components/ui/ChainOfThought.kt`：时间线卡片、整行 clickable、箭头翻转、animateContentSize、collapsed 折叠尾部步骤；
  - `ui/components/message/ChatMessageReasoning.kt`：三态 Collapsed/Preview/Expanded、生成中渐隐预览+自动滚底、完成后自动收回。
- **16:05** 定位 CloudBuddy 两个根因：`ThoughtRow` 标题行没有 `.clickable`（所以点了没反应）；`parseMarkdown` 把 `|...|` 行拍平成纯文本（所以表格不渲染）。
- **16:06** 方案：把 rikkahub 的 DataTable 与 ChainOfThought 交互整段移植，图标改用它已有的 material-icons-extended（不引 bettertext/HugeIcons 重依赖）；Markdown 解析器加真正的表格 block。
- **16:12** 新增 `DataTable.kt`（rikkahub 表格组件）、`ChainOfThought.kt`（rikkahub 时间线交互，图标换 material-icons）；`Markdown.kt` 加 `MdBlock.Table` 解析与渲染；`ChatParts.kt` 的 `ThoughtRow` 换成 rikkahub 三态交互并传 streaming。
- **16:16** 修 3 处编译错误（ExpandLess/ExpandMore 导入误删、Alignment 重复导入、DataTable 局部变量遮蔽参数）；编译通过。
- **16:18** 新增 `MarkdownTableTest` 6 个单测全绿；版本升 1.3.8 / versionCode 22；`verify.sh` 开跑。
- **16:22** `verify.sh` 全绿：126 单测、lint 0、正式签名；`dist/CloudBuddy-1.3.8-release.apk`，sha256 `fdd1e544...2856b`。
- **16:24** 提交 `5157335`，push main 与 tag `v1.3.8` 成功，CI 自动发版。

## 2026-11-24 · 折叠动画补全 + 输入框（进行中）

- **16:30** 用户澄清：要的是**整个折叠组**都有动画（不只是思考卡），且输入框也要抄 rikkahub。
- **16:31** 重写 `StepsGroup`：整行点击、箭头旋转 180°、`animateContentSize` 高度过渡，圆角改 20dp，标题升 titleSmall——与思考卡同一套 rikkahub 交互。
- **16:34** 编译遇到 Compose 编译器 `NotImplementedError: Unknown file`（增量缓存脏），`gradle clean` 后通过。
- **16:40** 抄 rikkahub 重做 `Composer` 输入框：圆角 Surface + 细边框容器、透明 BasicTextField + 占位符、附件 chip、模型 chip、圆形发送键（primary/禁用/error 三态 + fade/scale 动画）、ImeAction.Send 回车发送。
- **16:42** `gradle clean` 编译通过（增量缓存的 Compose 编译器 `Unknown file` 错误靠 clean 解决）。
- **16:44** `verify.sh` 全绿：126 单测、lint 0、正式签名；`dist/CloudBuddy-1.3.9-release.apk`，sha256 `9e60948f...01f1`。
- **16:45** 提交 `94c204c`，push main 与 tag `v1.3.9` 成功，CI 自动发版。

## 2026-11-24 · 卡顿排查与优化（1.4.0，进行中）

- **17:00** 用户反馈「现在有点卡顿」。逐层排查流式渲染链路：
  1. `AcpConnection.PUSH_INTERVAL_MS=60`：SSE 每 60ms 推一次快照 → UI 每秒 ~16 次全链路重组；
  2. `ChatScreen window=remember(ui.messages)`：每次推送 messages 换新 list，全量重跑 windowTimeline（filterNot/map/distinct/filter/projectTimeline）；
  3. `ThoughtRow LaunchedEffect(streaming, text)`：text 做 key，每 60ms 取消重启一次协程并 `animateScrollTo`，思考流式输出时持续重启滚动动画；
  4. `durationMs` 每秒写 state → 整个思考卡（ChainOfThought+MarkdownText）重组，只为更新「Xs」三个字；
  5. `StepsGroup` 外层 + `ChainOfThought` 内层双层 `animateContentSize`：步骤组默认展开，assistant 文本每 60ms 增长就反复触发高度测量+动画。
- **17:02** 方案：降推送频率、修滚动 effect 的 text key、隔离耗时计时、流式期禁用高度动画。均为局部改动，不动状态机与网络。
- **17:05** 落地四处优化：
  1. `AcpConnection.PUSH_INTERVAL_MS` 60→100ms（16fps→10fps，重组 -40%）；
  2. `ThoughtRow` 去掉 `LaunchedEffect(streaming, text)` 的 text key，改 `rememberUpdatedState` + `snapshotFlow{长度}` 采样 + 120ms 节流 + 无动画 `scrollTo`；
  3. 思考耗时抽成独立 `ThinkingSeconds` 组件，每秒只重组一行文字，不再拖整个卡片；`ReasoningState.durationMs` 移除；
  4. `ChainOfThought` 加 `animate` 参数，生成中关掉高度动画；`StepsGroup` 外层在运行中也关掉动画——消除双层 animateContentSize 在流式期反复测量。
- **17:08** `gradle clean compileDebugKotlin` 通过。
- **17:10** `verify.sh` 全绿：126 单测、lint 0、正式签名；`dist/CloudBuddy-1.4.0-release.apk`，sha256 `4e1aa4e1...8a4`。
- **17:12** 提交 `53fce43`，push main 与 tag `v1.4.0` 成功，CI 自动发版。

## 2026-11-24 · 会话永久「运行中」第三次复发（1.4.1）

- **17:0x** 用户报 `Refactor CloudBuddy UI using open source designs` 又卡在运行中。此前两次定位都在客户端本地（僵尸 prompt 流 1.3.6、滞留 working 事件 1.3.7）。
- **17:0x** 尝试用短信重新登录查云端 `sessionStatus` 区分「本地漏判 / 真在跑」；两次验证码都因导出 cookie 后 session 被轮换而失效，改写 `conv_state.py`（登录与查询同一 requests.Session）。
- **17:1x** 用户打断并给出关键新线索：本机同时装着 1.3.3 与最新版，**旧版里点进该会话后恢复正常，切回新版也跟着正常** → 说明服务端状态位是「agent 还挂着这条会话」，`session/load` 一挂上就收敛，不需要用户真看它。放弃继续查云端，直接按这条因果改代码。
- **17:2x** 读 `BuddyRepository.reconcile/reconcileAfterOpen/confirmIdle/send/openConversation` 与 `AcpConnection.setLocalTurnActive/initializeAndLoad`，确认两处闸门漏点：`active || replaying`、`endReplay` 不在 finally。
- **17:3x** 改 `StateReconciler`：新增 `shouldHeal` + 三个常量（IDLE 5 分钟 / 观察 2 分钟 / 最多 3 次）；`AcpConnection` 两处修正；`BuddyRepository` 新增 `settleIfStale`/`scheduleHeal`/`healStaleRun` 并在 `refreshConversations`、`reconcile`、`reconcileAfterOpen`、`confirmIdle` 四处接入。
- **17:4x** 新增 4 条 `shouldHeal` 单测；版本升 1.4.1 / versionCode 25。
- **17:5x** `verify.sh` 全绿：130 单测 / lint 0 error / 正式签名；`dist/CloudBuddy-1.4.1-release.apk`，sha256 `fe842208f5a06605d16088c9416713f4cbe481c3b8fbf69209ea54c524ab7c1e`。
- **17:5x** 补一条保护：本地连接还在推流（别的设备驱动）时不算滞留，避免自愈打断真任务；随 commit `8c22ae0` 重打 tag，CI 重建。

## 2026-11-24 · 同一问题补充证据，改成三层兜底（1.4.2）

- **17:5x** 1.4.1 打包期间用户补了一条更硬的事实：**旧版 1.3.3 里加载完历史就正常，最初触发这条会话的客户端重进、刷新都好不了**。
- **18:0x** 据此推翻 1.4.1 的因果：1.3.3 没有「打开后对账」，加载完只是本地把 running 擦成 IDLE，服务端 `status` 没变；新版采纳远端，所以重进无效。→ 服务端运行标志可能永远不自愈，不能等它收敛。
- **18:0x** 改 `AcpConnection.resetRemoteTurn`（请求形式 session/cancel）；`healStaleRun` 改成 load + cancel；`confirmIdle` 清不掉时降级放行（只认「本地有事件流 / 内容 5 分钟内更新」为真在跑）；`SessionsScreen` 菜单加「解除卡住的运行状态」→ `forceResetConversation`。
- **18:1x** 版本升 1.4.2 / versionCode 26；`verify.sh` 全绿：130 单测 / lint 0 error / 正式签名；`dist/CloudBuddy-1.4.2-release.apk` sha256 `c93e86a22dace03e7a53cfec19e25fc71f961739db6434ca06ed6440c8944d79`。

## 2026-11-24 · 大退重进后云端在跑却显示空闲（1.4.3）

- **18:2x** 用户反馈：布置任务后大退 App，重进时消息在加载、新消息往外冒，但右上角与输入框都显示空闲。
- **18:2x** 读 `AcpReducer.accept/updateState` 与 `AcpConnection.setLocalTurnActive/endReplay/close`：定位 `endReplay` 里 `allowRemoteRunning = localTurnActive`——大退重进后 localTurnActive=false，实时 working 被 `updateState` 挡掉，而消息分片不走该闸门，正好解释「文字在流、状态空闲」。
- **18:2x** 确认 `accept` 已用 `!history` 过滤回放期状态，实时期无需再按「谁发起的」拦截。改 `endReplay` 为 `true`、`setLocalTurnActive` 结束时不再关闸、`close` 保持 false。
- **18:2x** 新增回归测试（非本地发起的实时 working 点亮状态）；版本升 1.4.3 / versionCode 27。

## 2026-11-24 · 设置页打码 + 下载进度/打开 + 文件分流（1.4.4）

- **18:3x** 用户发设置页截图：顶部账号卡副标题显示完整手机号、下面账号行打码，要求统一。另提两点：下载加进度指示、完成弹窗「知道了」改「打开」；点击 APK 这类应用内打不开的文件应直接下载并跳转外部应用。
- **18:3x** 定位：顶部卡片 `SettingsScreen.kt:162` 直接用 `accountHint`；账号行走 `Account.maskHint`。下载 `readWorkspaceFile` 一次性 `body.bytes()` 无进度；notice 横幅固定「知道了」；点击文件非目录一律 `onPreview`。
- **18:3x** 实现：顶部改 `Account.maskHint`；`SandboxFs.read` 加流式进度回调、`BuddyRepository.readWorkspaceFile` 透传；`Downloads.open` 经 FileProvider + ACTION_VIEW，`file_paths.xml` 加公共 Download 授权；`SandboxPath.canPreviewInApp` 分流点击；工作区与预览页加进度条 + 「打开」按钮。
- **18:3x** 新增 `SandboxPathTest` 分流测试；版本升 1.4.4 / versionCode 28。

## 2026-11-24 · 四项体验修复（1.4.5）

- 思考折叠开关失效根因：`ThoughtRow` 非流式分支只处理 Preview，历史思考卡停在 Collapsed 不响应设置；改为双向跟随。
- 新增 `AppSettings.enterSend`；`Composer` 按 `ImeAction.Send/Default` 分支，关闭后回车换行。
- 关于区加「提交反馈」→ GitHub Issues。
- 工作区：`downloaded` 记录已下载文件，点击直接打开；下载气泡加「取消」（`SandboxFs.read` 流式循环检测取消标记抛 CloudException 499）。
- 版本升 1.4.5 / versionCode 29。

## 2026-11-24 · 折叠组动画与小字（1.4.6）

- 用户问执行中的折叠组没有展开/折叠动画是否 bug；确认是 1.4.0 性能规避，现恢复 `animateContentSize` 全程生效，并删除「展开/收起」尾注文字。版本 1.4.6 / versionCode 30。

## 2026-11-24 · 旁观时角标提前跳「已完成」（1.4.7）

- 用户实测任务就是在 App 里发起的（初判「旁观」不成立）：agent 等后台工具期间，中途的 completed 上报把角标顶成「已完成」，工具卡还在转圈。旧 updateState 只挡 IDLE 不挡 DONE，与谁发起无关，守卫改按「有 RUNNING 工具」判定即可覆盖两种场景。
- 修 `AcpReducer.updateState`：有 RUNNING 工具时不采信 DONE/IDLE 上报；`session/endTurn` 收尾清理残留转圈工具。新增 2 条测试，134。
- 版本 1.4.7 / versionCode 31。

## 2026-11-24 · 1.4.7 守卫补静默间隙（1.4.8）

- 用户问「还会不会出现任务在跑却显示已完成」；盘点发现工具间隙（无 RUNNING 工具 + 本地轮次）仍漏。
- updateState 终态拦截扩为 `turnActive || hasRunningTool`；新增静默间隙回归测试，135。版本 1.4.8 / versionCode 32。
