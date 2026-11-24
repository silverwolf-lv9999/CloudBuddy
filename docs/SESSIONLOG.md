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
