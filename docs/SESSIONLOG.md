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

