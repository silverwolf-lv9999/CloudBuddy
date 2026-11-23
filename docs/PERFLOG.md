# 性能优化日志（1.0.2）

> 背景：用户反馈「现在感觉有点卡」。本文件记录排查路径、定位到的具体热点、每处改动的依据与验证方式，
> 便于上下文压缩后回溯，也便于后续继续优化。

## 一、排查方法

没有真机可跑（构建环境无 KVM），因此从「帧预算」角度静态审查流式输出路径上的每一跳：

```
SSE 行 → receive() → reducer.accept() → publish() → onUpdate → repo.publish()
      → _ui.value 发射 → collectAsState → ChatScreen 重组 → LazyColumn → MessageRow → parseMarkdown
```

逐跳检查「一次分片会触发多少工作」，凡是「每个 token 都做 O(n) 全量活」的地方即为热点。

## 二、定位到的 6 个热点

| # | 位置 | 问题 | 量级 |
| --- | --- | --- | --- |
| H1 | `ChatScreen` 的 `LaunchedEffect` | 每个分片都调 `animateScrollToItem`，上一次动画被打断重开，相当于持续抖动 | 每 token 一次动画重启 |
| H2 | `AcpConnection.publish()` | 每个 SSE 事件直接推送一次快照，不合并 | 每秒 30–100 次发射 |
| H3 | `BuddyRepository.publish()` | 连续两次 `_ui.value = copy(...)`（两次发射），且每次都 `conversations.map{}` 重建整个会话列表 | 每次推送 2 次发射 + 全列表重建 |
| H4 | `AcpReducer.append()` | `last.copy(text = last.text + text)` 每片都复制整段已累积文本 | O(chunks × len)，平方级 |
| H5 | `AcpReducer.snapshot()` | 每次 `messages.toList()` + `artifacts.values.toList()`，无缓存 | 每 token 两次列表复制 |
| H6 | `Markdown.parseMarkdown` | `Regex("""^\d{1,3}[.)]\s""")` 在逐行循环内**每行重新编译**；`matches(Regex(...))` 同样 | 500 行正文 → 上千次正则编译 |
| H7 | `MarkdownText` | 流式消息每帧全量重解析（含所有已完成段落） | 每帧解析 N 行 |

H1 是「手感卡」的直接来源，H2–H5 决定了重组频率，H6–H7 决定了单帧耗时。

## 三、改动

### 1. 滚动跟随改为「贴底部才跟随 + 瞬时滚动」（`ChatScreen.kt`）

- `animateScrollToItem` → `scrollToItem`：不再产生可被反复打断的动画事务。
- 用 `snapshotFlow` 观察 `isScrollInProgress / canScrollForward`，用户上滑查看历史时自动关闭跟随，
  回到底部再恢复——避免「想翻历史却被强行拽回底部」。
- 顺带修掉一个真实缺陷：原实现每片都 `animateScrollToItem(lastIndex)`，长回答时用户根本无法上滑。

### 2. 推送节流（`AcpConnection.kt`）

- `publish()` 加 60ms 时间窗合并：窗口内的多次更新只推一次，并安排一次 trailing flush 保证**末片不丢**。
- 终态（`finishTurn` / `markStopped` / `close`）走 `publish(force = true)`，界面不会停在「执行中」。
- 用单线程 daemon 调度器，不依赖主线程 Handler，JVM 单测可跑。

### 3. 单次发射 + 列表按需重建（`BuddyRepository.kt`）

- 两次 `_ui.value =` 合并为一次，发射次数减半。
- 会话列表只在 `state / artifacts / ask` 变化时重建；纯文本分片不再重建整个 `conversations`，
  会话列表页不会跟着抖动。
- 增加「无变化直接 return」的守卫（依赖 H5 的快照缓存让引用比较可短路）。

### 4. 流式文本用 StringBuilder 累积（`AcpReducer.kt`）

- 增量分片写入 `streamText`，不再每片 `copy(text = ...)` 复制整段。
- `closeStream()` 时才 flush 回 `messages`，语义与原来完全一致（换角色 / 工具调用 / 轮次结束的边界不变）。
- 效果：从 O(chunks × len) 降到 O(len)，长回答时 GC 压力显著下降。

### 5. 快照缓存（`AcpReducer.kt`）

- `snapshot()` 增加 `dirty` 标志与缓存对象：无变化时返回**同一实例**。
- 这同时让 `StateFlow` 的去重和 `AppUiState.copy()` 的比较走引用快速路径。

### 6. Markdown 解析提速（`Markdown.kt`）

- 循环内 `Regex(...)` 全部提为文件级常量（只编译一次）。
- 增加前缀增量解析：以「代码块外的 `\n\n`」为安全切点，已完成段落的结果直接复用，
  只解析尾部新增内容。流式场景下每帧解析量从「整段」降到「最后一段」。
- 切点必须避开代码围栏内部，否则会把一个代码块拆成两段；用「切点前 ``` 计数为偶数」判定。

## 四、验证

- 单测：新增 `MarkdownCacheTest`（增量解析与全量解析结果必须完全一致，含代码块跨切点用例）、
  `PerfRegressionTest`（流式累积结果与逐片拼接一致、快照缓存不返回过期数据）。
- 全量 `assembleDebug` + `testDebugUnitTest` + `lintDebug`。
- 无真机，因此**帧率数据无法实测**；以上均为结构性优化，收益方向确定，幅度需真机确认。

---

# 追加（1.0.7）：长会话的首次渲染开销

长对话打开慢有两个来源，1.0.2 只解决了其中一个。

## 一、回放期完全不推送（原本是对的，第一版改错了）

`AcpConnection.publishCoalesced()` 原本 `if (replaying) return`：整段历史回放期间
界面收不到任何快照。这条其实是对的——回放期推消息会让界面看到历史逐条长出、
历史里的工具调用转圈，观感上像在「重放当时的步骤」。

1.0.6 那版（已作废，号被复用，最终内容改挂 1.0.7）把它改成 400ms 合并推送，
被用户否掉，已回退。现在折中：

- 回放期仍然不推消息，只每 300ms 报一次「已收到 N 条」（`onReplayProgress`），
  这只是更新一行文字，不触发列表重建；
- 收尾时 `finishReplay()` 把历史收敛成终态，再一次性推全量；
- 静默判定（1.5 秒无事件）让它通常 1~2 秒就收尾，而不是干等 `session/load` 响应几十秒。

## 二、一次布局几千个 item

`projectTimeline()` 是 O(n)，`LazyColumn` 首次布局又要测量全部可见项并且要为每个 item
计算 key/位置。几千条消息时，打开会话的卡顿主要来自这里，而不是网络。

`windowTimeline(messages, turns)` 先在**消息层面按轮裁剪**再投影：

```kotlin
val order = visible.map { it.turn }.distinct()          // turn 单调不减
val keepFrom = order[order.size - turns]
projectTimeline(visible.filter { it.turn >= keepFrom }) // 只投影尾部窗口
```

- 必须按轮裁：按条裁会把一轮的步骤组切成两半，折叠摘要里的计数就错了；
- 默认 20 轮，往上点「显示更早」再扩一页（`extraTurns`），锚点用 item key 恢复滚动位置；
- 代价：每次重组仍是 O(n)（`filter` + `distinct` 两个全量遍历）。3000 条量级下约 0.3ms，
  60ms 一次的推送频率下可以接受；真机上若仍有压力，下一步可以把「轮 → 起始下标」
  用倒序扫描改成 O(window)。

## 三、验证

- `TimelineProjectionTest` 新增 5 项开窗用例（尾部窗口、步骤组不被切碎、0 = 全部、
  短会话不开窗、空白分片不计数），共 55 项全绿。
- 仍是**无真机**：窗口轮数、回放合并间隔这两个值需要真实长会话复核。
