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

