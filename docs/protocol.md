# WorkBuddy 云端协议笔记

本文件记录客户端实际使用的接口与帧格式，来源是公开 web 端行为 + [Wearbuddy](https://github.com/Oride-it/Wearbuddy) 的复原结论，并在本项目中重新实现（OkHttp / Kotlin）。所有请求都带浏览器式 cookie，`Referer` 指向站点 `/app`。

## 1. 统一信封

```json
{ "code": 0, "msg": "", "data": { } }
```

`code == 0` 或缺省视为成功；`data` 缺失时回退到根节点。登录失效表现为两条路径：

- HTTP 401，或
- 302 被重定向回登录页（跟随重定向后拿到的是 HTML）。

两者都会触发一次静默续期（`bootstrapConsoleSession`），每次请求最多重试 2 次。

## 2. 登录（auth-v2）

| 步骤 | 请求 |
| --- | --- |
| 建会话 | `GET /auth-v2/realms/copilot/signin/phone/login?platform=web` → `{session_state}` |
| 发短信 | `POST …/phone/sms` `{session_state, phone:"+86…"}` → `{status:ok, retry_after}` |
| 校验码 | `POST …/phone/verify` `{session_state, phone, sms_code, skip_enterprise:true}` → `{status:ready_to_login, ticket, nickname}` |
| 换 code | `GET …/finalize?ticket=…&response_type=code&session_state=…` → `{code}` |
| 换会话 | `POST …/session` `{code, issuer, redirect_uri}` |
| 种网关会话 | `GET /console/accounts`（不跟随）→ 读 `Location` → `GET` 该地址（跟随） |
| 校验 | `GET /console/as/conversations/?type=cloud&page=1&size=1`，要求 `code==0` |

要点：

- 续期前必须先丢掉网关 `session` / `session_2`，网关只有在完全没有该 cookie 时才会 302 到 Keycloak。
- SSO cookie 必须保留，否则续期要重新收短信。
- 网关 `session` 与登录时的 UA 绑定，登录和业务请求共用同一个 UA。

## 3. 会话

| 用途 | 请求 |
| --- | --- |
| 列表 | `GET /console/as/conversations/?type=cloud&page=1&size=30&conversationOrigin=agents,workbuddy-mp,workbuddy-app,openapi,workbuddy-deploy&sort={"orderBy":"updatedAt","order":"desc"}` |
| 新建 | `POST /console/as/conversations/` `{prompt, model, plugins:[{name:"weixinpay",marketplace:"codebuddy-builtin"}]}` |
| 详情 | `GET /console/as/conversations/{id}` |
| 接入点 | `GET /console/as/conversations/{id}/session` → `{link, token, cwd, sandboxId, sessionId?, e2bDataEndpoint?}` |
| 重命名 | `POST /console/as/conversations/{id}` `{title}` |
| 归档 | `POST /console/as/conversations/{id}/archive` |
| 删除 | `POST /console/as/conversations/{id}/delete` `{id}` |

**新建时携带的 `prompt` 只用于生成标题**，真正的首轮指令必须通过 ACP `session/prompt` 下发，否则会话会停在云端队列里。

模型列表有两个来源，**ACP 为主、REST 为辅**：

- `session/load`（或 `session/new`）的 RPC 响应 `result.models.availableModels`、
  或 `result.models._meta["codebuddy.ai"].availableModels`，以及 `models.currentModelId`。
  这是官方 `extractAvailableModels()` 的做法，只要 ACP 连得上就有，不依赖 console 配置接口。
- `GET /console/enterprises/{personal|企业ID}/models`。只有 `agents[name=cli].models`
  里的 ID 才是账号真正可用的子集；`disabled` 的要过滤。`credits` 字段是 `x0.21` 这类
  展示串，原样显示即可。企业账号需在请求头带 `X-User-Id` / `X-Enterprise-Id` /
  `X-Tenant-Id`（值取自 `/console/accounts` 的 `uid` / `enterpriseId`）。

切换模型用 ACP `session/set_model`；此外每一轮 `session/prompt` 的
`_meta.codebuddy.ai.model` 与各内容块的 `_meta.codebuddy.ai.model` 都会带上所选 ID，
双重保证下轮生效。

## 4. ACP 长连接

### 握手

```
GET {link}                                  # http 会被强制升级成 https
Authorization: Bearer {token}
Accept: text/event-stream
```

响应头给出：

- `Acp-Connection-Id`：后续每个 POST 必须带
- `acp-session-token`：有则带

然后 JSON-RPC 2.0：

```
POST {link}
Authorization: Bearer {token}
Acp-Connection-Id: {connId}
acp-session-token: {token?}
Content-Type: application/json
```

帧：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,
 "clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false}}}}
{"jsonrpc":"2.0","id":2,"method":"session/load","params":{"sessionId":"…","cwd":"/workspace","mcpServers":[]}}
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"…","cwd":"/workspace",
 "mcpServers":[],"prompt":[{"type":"text","text":"…"}],
 "_meta":{"codebuddy.ai":{"conversationId":"…","model":"…","extra":{"sourceContentBlocks":[…]}}}}}
{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"…"}}         // 通知，无 id
{"jsonrpc":"2.0","id":4,"method":"session/set_model","params":{"sessionId":"…","modelId":"…"}}
{"jsonrpc":"2.0","id":5,"method":"_codebuddy.ai/resolveInterruption","params":{"sessionId":"…","requestId":"…","toolCallId":"…","optionId":"…","answer":"…"}}
```

- 历史回放与实时事件共用同一条 GET 流；不要为刷新历史再开一条连接，否则实时进度会与界面脱钩。
- POST 的响应也可能是 `text/event-stream`，要按 SSE 解析。
- 握手（取接入点 + initialize + load）可重试 3 次；`session/prompt` 绝不能在重试循环里跑。

### 事件

`session/update` 的 `update.sessionUpdate`：

| 类型 | 处理 |
| --- | --- |
| `user_message_chunk` | 用户消息增量；用「已发送文本」做前缀回显去重 |
| `agent_message_chunk` | 助手正文增量，空白分片不能丢 |
| `agent_thought_chunk` | 思考增量，UI 折叠 |
| `tool_call` / `tool_call_update` | 以 `toolCallId` 定位，只保留标题 + 状态；内容里可能带产物链接 |
| `session_info_update` | `_meta["codebuddy.ai/status"]` 给状态；`isSnapshot` 的历史值不能判定新一轮完成 |
| `state_update` | `currentState` 给状态 |

完成信号（四选一即可）：prompt RPC 的 `result.stopReason`、`session/endTurn`、`state_update`、`session_info_update` 的状态位。`stopReason` 映射：`cancelled/canceled → 已停止`、`refusal → 失败`、其他 → 完成。

产物通知：`_codebuddy.ai/artifact`（也可能嵌在 `extNotification` 里），字段 `event`（`deleted` 表示移除）、`artifact.uri`、可选 `url`。

子 agent 的事件（`_meta["codebuddy.ai/isSubAgent"]`）要丢弃，否则主对话会被子任务刷屏。

### 轮次（turn）与中间步骤折叠

归约器给每条消息打一个 `turn` 编号：`beginTurn()` 时递增，历史回放里遇到新的
`user_message_chunk` 时也递增。界面据此把同一轮的 `agent_thought_chunk`、
`tool_call`、过程性 `agent_message_chunk` 收进一个组，只保留用户提问与
**该轮最后一条非空助手消息**作为最终回答。

折叠粒度参考 AgentsAnywhere 安卓端：`groupTimelineMessages()` 把连续的工具/推理
事件聚合成 `ToolRun` / `AgentCalls`，组头渲染一行摘要（`Chevron + 图标 + 摘要`），
展开状态由 `TimelineReadingState` 按消息 id 记录。

## 5. 沙箱文件网关

会话接入点形如 `https://8080-{sandbox}.e2b.{region}.sandbox.cloudstudio.club/acp`。文件服务是同一个沙箱的 **49983** 端口：

```
https://49983-{sandbox}.e2b.{region}.sandbox.cloudstudio.club
```

若会话 JSON 里直接给了 `e2bDataEndpoint` / `sandboxDataLink` / `dataEndpoint`，优先用它（统一升级成 https）。

| 操作 | 请求 | 头 |
| --- | --- | --- |
| 列目录 | `POST {ep}/filesystem.Filesystem/ListDir` `{"path":"/workspace","depth":1}` | `Connect-Protocol-Version: 1`、`Content-Type: application/json` |
| 新建目录 | `POST {ep}/filesystem.Filesystem/MakeDir` `{"path":…}` | 同上 |
| 删除 | `POST {ep}/filesystem.Filesystem/Remove` `{"path":…}` | 同上 |
| 移动/重命名 | `POST {ep}/filesystem.Filesystem/Move` `{"source":…,"destination":…}` | 同上 |
| 读文件 | `GET {ep}/files?path={encodeURIComponent(path)}&username={user\|root}&cb={ts}` | `X-Access-Token: {token}` |
| 写文件 | `POST {ep}/files?path={…}&username={user\|root}`，`multipart/form-data`，字段名 `file` | 同上 |

所有请求统一带 `X-Access-Token: {session.token}`。`username` 由路径推导：
`/root` 及其子路径用 `root`，其余（含 `/workspace`）用 `user`——官方 `r2r()` 的规则，
缺省该参数会让网关返回 404。

**来源**：官方 Web 端 `main-*.js` 的 `CloudFileManager.listDirectory()` /
`CollabCloudE2BFilesystem.rpc()`，以及 E2B envd `filesystem.Filesystem` Connect RPC 文档。
此前猜测的 `/filesystem/list`、`/files/list` 等 REST 路径并不存在，会全部 404。

`ListDir` 返回 `{"entries":[…]}`，条目字段：`name`、`path`、`type`、`size`、`mode`、
`modifiedTime`、`symlinkTarget`。`type` 为 `1`/`FILE_TYPE_FILE`（文件）或
`2`/`FILE_TYPE_DIR`/`FILE_TYPE_DIRECTORY`（目录）；`modifiedTime` 是 ISO 字符串或
`{seconds, nanos}`。客户端也兼容裸数组与 `result` / `files` / `data` 包装。

**沙箱过期恢复**：401/403/404 时重新 `GET .../session` 取新的接入点再试一次。
旧的 49983 网关地址会随沙箱重建而失效，直接重试同一地址只会继续 404。

路径只允许 `/workspace/…` 与 `/root/…`，解码后检查 `..`、`\`、`\0`（包括双重编码的穿越）。

## 6. 附件

两种通道：

1. **优先**：先 `POST {ep}/files?path=/workspace/uploads/cb-{ts}/{name}&username=root` 写入沙箱，再把 `resource_link` 块（`_meta` 里带 `sandboxPath`）交给 agent，并追加一段隐藏提示列出沙箱路径。
2. **回退**：≤8MB 的图片用 `{type:"image", mimeType, data(base64)}`，其他文件用 `{type:"resource", resource:{uri, mimeType, blob}}`。

对象存储直传（`POST /console/as/support/presigned_url` → `upload_url` PUT）用于非沙箱场景；下载沙箱文件走上面的 `/files`，下载对象存储文件走预签名 `download_url`（键有原样/解码/重编码三种写法，逐个尝试）。

## 7. 状态推断

`TurnState` 由三处输入合成，优先级：ACP 快照 > REST 轮询 > 本地显式停止。

- ACP：事件里最新的 `status` / `currentState`
- REST：`status` 字段（`running/working/completed/failed/cancelled/…`），或嵌套 `status.state`
- 本地：用户点「停止」后，即使远端仍报 `RUNNING` 也保持 `STOPPED`，直到下一轮发送
