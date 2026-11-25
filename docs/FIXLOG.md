# CloudBuddy 修复日志

## 本轮任务（用户反馈）
1. **无法选择模型**
2. **工作区打开 404**

## 约束
- 保留官方云端沙箱工作区（cwd=/workspace），不退化成本地 API 反代。
- 每步执行都记录日志，防止上下文压缩丢失信息。

---

## 1.4.12（同步上游；一份代码出三个包）

> 版本号说明：`origin/main` 停在 1.4.11 / 35，所以本版接着是 **1.4.12 / 36**。
> 中途的 1.5.0–1.7.0 是本地测试线，从未对外发布，那些号不占位，内容全部并入本版。
> 代价是 versionCode 从测试版的 49 回落到 36——**装过测试包的手机会拒绝降级安装**，
> 需要先卸载（数据会丢，登录一次即可）。

### 一、同步上游最新提交

上游这次只动了两处：`BuddyRepository` 里「工具调用中重进」的判定，以及版本号。
我改的部分（终端多标签、设置二级页、模型管理）跟它不冲突，`rebase` 之后只剩三处要手工合：

| 文件 | 冲突 | 处理 |
|---|---|---|
| `app/build.gradle.kts` | 上游 1.4.11 / 本地 1.6.6 | 取本地，再升 1.7.0 |
| `README.md` | 版本行 | 取本地，补三包说明 |
| `BuddyRepository.kt` | `suspectEnd` 判定 | 保留上游新增的条件，同时保留本地「补历史」分支 |

`BuddyRepository` 那处值得留一笔：两边的改动都在同一个 `if` 的头部，谁覆盖谁都会丢功能——
上游加的是「`!activeLocal`」（本地请求在途时不采信远端终态），本地加的是「`suspectEnd`」
（历史停在未完成工具上时不采信）。两个条件必须同时成立才算可采纳，所以是**合并**而不是二选一。

### 二、三个包：基础版 / 完整版 / 共存版

用户的要求分两步：先要「完整版和不带 agent 的版本各一个」，再追加「完整版要两种——
一种与基础版同包名可互相覆盖，一种包名不同可共存」。

**包名分配**（`edition` 维度，三个 flavor）：

| flavor | applicationId | 内容 | 关系 |
|---|---|---|---|
| `base` | `cn.cloudbuddy.app` | 云端模型 | 与 `full` 互斥 |
| `full` | `cn.cloudbuddy.app` | 云端 + 本机 agent + 第三方 API + 终端 | 覆盖 `base` 即升级，数据保留 |
| `plus` | `cn.cloudbuddy.app.plus` | 同 `full` | 可与前两者并存 |

关键取舍：

- **`base` 与 `full` 必须同包名**，否则「覆盖安装」无从谈起。为此把 `defaultConfig.applicationId`
  从 `.plus` 改回上游的 `cn.cloudbuddy.app`——`.plus` 只留给共存版做后缀。
- **三个包应用名都叫「云伴 WorkBuddy」**，也就是云端仓库原本的名字（用户要求：基础版
  必须沿用云端名字，三个包同名）。基础版与完整版同包名又同名，覆盖安装后桌面上完全看不出
  差别，这正是"两个档位"该有的样子；代价是共存版与它们并排时也是同名图标，
  只能靠「应用信息」里的包名区分，文档里写清楚。
- **不加 `versionNameSuffix`**：三个包同一版本号就是同一份代码，`1.7.0` 全一样。
  `scripts/verify.sh` 会断言三个包的 versionName 完全相等。
- `plus` 不复制代码：`sourceSets["plus"].java.srcDir("src/full/java")` 直接挂 full 的源集。
  复制一份的后果是改一个 bug 要改两处，第二次总会漏。

### 三、能力隔离：不在依赖图里，也不在 dex 里

之前只是「把入口藏起来」，`TerminalScreen` 的类仍然打在基础版里。这次彻底分开：

```
src/main      共用：网络、会话、设置、UI 骨架、AgentContracts（接口与数据类）
src/base      AgentSupport 空实现 + TerminalScreen 占位页
src/full      SessionAgent / CustomLlmClient / SandboxTools + TerminalScreen 完整实现
src/testFull  agent 三个测试类
```

- `ai.koog:agents-core`、`prompt-executor-llms`、`ktor-client-okhttp` 改成 `fullImplementation`
  与 `plusImplementation` 两条，基础版的依赖图里没有它们。
- **`kotlinx-serialization-json` 必须显式声明**：它原先只是 Koog 的传递依赖，基础版不含 Koog 后
  数据层直接编译失败（`Json` 找不到）。版本钉在 1.8.1，与 Koog 的约束一致。
- `SessionAgent` 加 `: AgentSession`、`SessionAgentHub` 加 `: AgentRuntime`，接口全部落在 main 的
  `AgentContracts.kt`；仓储层只认接口，`customAgents = AgentSupport.create(...)`。
  `testConnection` 从仓储里搬到实现侧——基础版连这段代码都不该编译。
- 基础版启动时把 `engineMode` 压回云端：偏好里可能残留 `custom`，而设置页不显示引擎选择，
  否则用户会看到「自定义模型未配置完整」却找不到能改的地方。

验证不靠人眼，直接数 dex 里的字符串：

| 包 | `ai/koog` | `TabStrip` | 占位页文案 | 体积 |
|---|---|---|---|---|
| base | 0 | 0 | 有 | 18 MB |
| full | 4089 | 38 | 无 | 22 MB |
| plus | 4089 | 38 | 无 | 22 MB |

### 四、脚本与 CI 跟着改三份

- `scripts/verify.sh`：`EDITIONS=(base full plus)`，新增 `--base/--plus`；产物名
  `CloudBuddy-<版本>-{base,plus}-<mode>.apk`（full 不带后缀，仍是最常下的那个）；
  **新增包名断言**——包名错就没法覆盖安装，这件事不能靠人记得，必须脚本卡住。
- `scripts/build.sh`：三个 assemble 任务与三行产物路径。
- `.github/workflows/release.yml`：Release 说明改成三行表格，写清「基础版与完整版同包名、
  装完整版即升级」与「共存版包名不同可并存」；三个包都作为资产上传。

### 五、校验

- `scripts/verify.sh --debug` 全绿：三个变体单测（base 221 / full 241 / plus 241），
  lint 三个变体各 **0 error / 3 既有 warning**。
- `aapt2 dump badging` 确认：`base`/`full` = `cn.cloudbuddy.app`，`plus` = `cn.cloudbuddy.app.plus`，
  三者 versionCode 36、versionName 1.4.12、应用名「云伴 WorkBuddy」。

---

## 1.6.6（账号栏去重；设置页滑动优化）

### 一、首页与「账号与额度」页互相重复

**现象**：1.6.5 在首页补了整栏，但同一批信息二级页又写了一遍——积分余额、签到按钮、
额度前三项两处都在；首页还多一行入口标题「管理账号与额度」，跟组标题「账号与额度」
几乎同名，看两遍才知道是同一个地方。

**判定标准**（写进 AGENTS）：同一句话在两页各出现一次 = 重复；同一件事的
「摘要 / 明细」= 分层。摘要可以短，但不能是同一句。

**改法**：

- 首页只留「摘要 + 每天要点的签到」：账号行（整行点进二级页，尾部箭头）、
  积分余额与等级、连续签到天数与签到按钮、签到失败原因。
- 二级页只留「首页放不下的」：多账号列表与删除、添加账号、**完整**套餐额度
  （含有效期，首页不再列前三项）、云端环境。
- 删掉二级页的「刷新账号资料」行：首页账号行右侧的刷新已经一次刷昵称、积分、签到状态，
  两个刷新入口只会让人猜哪个才是真的。
- 删掉首页那条与组标题同名的入口行：账号行本身就是入口，箭头已经说明能点。

### 二、设置页列表滑动掉帧

三处确定的绘制/组合成本，逐条改：

1. **每张卡都带 1dp 阴影**。`Card` 默认 elevation 1dp，阴影要按模糊半径扩大脏区重绘；
   一屏十几张卡滚动时每帧都在画阴影。`SettingCard` 改用 `Surface`（0 阴影），
   分层靠 `surface` 与 `background` 的色差，观感几乎无差。
2. **分组标题与卡片共用一个 contentType**（默认都是 null）。LazyColumn 复用时会在
   「标题槽位」和「卡片槽位」之间反复丢弃再重建组合树，滚动经过交界处就会顿一下。
   新增 `LazyListScope.title()`：标题项单独 `key` + `contentType = "title"`，
   与卡片槽位分开。
3. **「我的模型」是设置页唯一"一次全画"的长列表**。模型攒到几十个时，单个 item
   高度上千 dp，组合与测量一次做完。默认先组合前 8 个，需要时点「显示全部 N 个」。

**没做、也不打算硬做的**：外层 `AnimatedContent` 的 tab 淡入（160ms，只在切页时），
以及模型页里那些 `OutlinedTextField`——它们是内容本身，不是装饰，删不掉。
真机如果还卡，下一步是把模型页的每个设置项拆成独立 item，让 LazyColumn 真正按行复用。

## 1.6.5（设置首页补上「账号与额度」这一栏）

**现象**：设置页从顶到底都没有「账号与额度」——不是被折叠进二级页，而是从来就没有过。

**根因**：拆二级页时只做了「账号与额度」这个**二级页**，首页只留了一张能点的昵称卡：

1. 没有分组标题，扫一眼列表只看到「推理 / 界面 / 扩展 / 其他」四组，账号仿佛不属于设置；
2. 积分、等级、签到、套餐额度全在第二层，首页一个字都看不到；
3. 上一轮把签到积分做成一张独立小卡挂在首页，仍然没有「账号与额度」这个名字，
   用户还是认不出来——他找的是一个**栏目**，不是一张卡。

**修法**：首页第一组改成完整的一栏。

- `SectionTitle("账号与额度", AccountCircle)` + 一张卡，四段用细分隔线连起来：
  1. 账号行：头像首字 + 昵称 + 脱敏手机号，右侧刷新（一次刷昵称、积分、签到状态）；
  2. 积分行：余额与等级、连续签到天数 / 可得积分、签到按钮、`checkinError`；
  3. 套餐额度：只列前三项（`剩 x / y`），完整清单留在二级页，避免把这栏撑成半屏；
  4. 入口行「管理账号与额度 ›」：添加/切换账号、套餐明细、云端环境。
- 头像从图标改成昵称首字：多账号时首字比通用头像更容易认人。
- 刷新按钮从「刷新」小胶囊改回图标：一行里塞文字按钮会把昵称挤成省略号。

**没动的**：二级页 `AccountSettingsPage` 原样保留（多账号列表、删除账号、服务站点、
会话模型、执行位置都在那），首页这一栏只是把「用户每天要看的三行」提到第一层。

## 1.6.4（终端跟随滚动；设置页去重、签到我回来了）

### 一、有新输出时页面停在这段的开头

**现象**：输出不断刷出来，但视图停在开头，看不到最新几行。

**根因**：贴底判定用的是几何状态——`visibleItemsInfo.last.index >= total - 2`。
实时输出是**同一个列表项在变高**，内容一变长 `canScrollForward` 立刻变 true，
下一次重组时判定为"不贴底"，跟随就此断掉。而 LazyColumn 会保持
`firstVisibleItemIndex`，于是画面钉在这一块的开头。

**修法**：把"要不要跟随"从几何状态改成**手势事件**。

- `follow` 只在**用户手指离开屏幕那一刻**重新判定：滚完还停在倒数两项之外，
  才认定为"在看历史"。
- 内容自己变长不再影响 `follow`——这正是关键，否则"一来新内容就停止跟随"。
- 滚动目标从 `scrollToItem(Int.MAX_VALUE)` 改成先 `withFrameNanos {}` 等布局，
  再滚到 `layoutInfo.totalItemsCount - 1`：条目数还没更新时，MAX_VALUE 会被
  coerce 到 0（这也是"跳回开头"的另一个来源）。
- 「最新」浮标的显隐改跟 `follow` 走：`atBottom` 在内容变长瞬间会闪。

### 二、设置页：推理入口重复

「推理引擎」和「自定义 API」是两个二级页，但推理页里又有「自定义 API 配置 →
端点与模型」的入口，点进去还是那堆东西。首页也要点两次才到端点配置。

合并成一页「**模型与推理**」：顶部引擎选择（云端 / 自定义，含两套会话不互转的说明），
往下是端点、模型管理、参数、思考强度、系统提示词。首页只剩一个入口。
`SettingsPage.Engine` 连同 `EngineSettingsPage` 一起删掉。

### 三、设置页：签到和积分不见了

拆二级页时把它们挪进了「账号与额度」，结果首页看不到——天天要点的东西不该藏在第二层。
首页账号卡下方加一张卡：积分余额 + 连续签到天数 + 签到按钮 + 失败原因。
`CheckinButton` / `checkinHint` 从 `private` 提为 `internal`，两处共用。

---

## 1.6.3（终端多标签）

**需求**：终端要能开多个窗口，像 Termux 那样一个跑构建、一个看日志。

### 数据层

- 新增 `TerminalTab`：一个标签 = 一份命令记录 + 一个执行槽（`running` /
  `runningCommand` / `liveOutput`）。
- `TerminalState` 改成 `tabs + activeTabId`，但保留 `entries` / `running` /
  `liveOutput` 这几个"当前标签"的便捷属性——绝大多数 UI 只关心眼前这个标签，
  让它们到处写 `state.active?.entries` 只会更啰嗦。
- `CommandLog` 的分区键从「会话 id」改成 `会话#标签`（`CommandLog.keyOf`）。
  `forget()` 改成按前缀清，否则删会话时标签的日志会留在内存里。
  分隔符用 `#`：`c1` 清不掉 `c10`（两个会话 id 有包含关系时前缀匹配会误伤）。

### 运行态

- `shellJob` 单槽 → `shellRuns: Map<tabId, ShellRun>`。**标签之间互不打断**：
  标签 1 跑 `npm install`，标签 2 照样能敲命令；同一标签内仍是一次一条
  （`sh -c` 没有作业控制，并发只会让输出串台）。
- 后台标签的输出照收（进 `ShellRun.live`），但不刷 UI；切回来时
  `selectTerminalTab` 从执行槽把已收的部分接上，进度不会断。
- 停止/清屏/关标签都带 `tabId` 参数，不再假设"只有一条在跑"。
- `stopAllShells()`：离开终端、切会话时把所有标签的命令收尾落盘。

### UI

- 标题栏下加标签条：`+` 新建（上限 5）、点切换、长按重命名、多于一个才显示 `×`。
  运行中的标签带一个琥珀色圆点——切走之后那边还在跑，没有这个点完全看不出来。
- 每个标签一份 `LazyListState`：切回来停在原来看的地方，不被动拽到底。
- 新建标签编号取「现有最大 + 1」，关掉中间的标签后不会和历史重名。

### 取舍

- **agent 跑的命令固定写进第一个标签**（`ensureDefaultTab`）。它不指定标签，
  但记录必须有归属；放第一个，用户进终端在最左边就能看到"模型到底执行了什么"。
- **输入框是全标签共享的**：敲了一半切去看日志，回来内容还在。每个标签独立
  草稿更像本机终端，但那要有独立 shell 才有意义，我们每条命令都是独立 `sh -c`。
- **标签只在同一会话内存在**：切会话整套换掉（沙箱不同，混着看比看不到更误导），
  切回来按 `tabMeta` 恢复原来的标签和编号。

---

## 1.6.2（终端：按停不再丢输出；输出按 ANSI 着色）

### 一、按停 = 清屏（用户报的严重 bug）

**现象**：`ping qq.com` 跑起来输出正常，点中断后**所有内容消失**。

**根因**（`BuddyRepository.runShell/stopShell`）：

1. 运行中的输出只存在 `TerminalState.liveOutput`——一个"正在跑"的临时字段，
   不进 `CommandLog`。这是当初为了性能做的（每个 chunk 全量刷新条目列表会把
   LazyColumn 拖垮）。
2. `stopShell()` 直接 `cancel()` 协程 + 把 `liveOutput` 刷成空。
3. 协程被取消时 `SandboxExec.execute` 会 `throw CancellationException`，
   所以 `runCatching { withSandbox {...} }` 拿到的是 failure，落盘时
   `result.stdout` 是空的。

三条叠起来：输出既没进日志、又没留在屏幕上，用户看到的就是"全没了"。

**修法**：给"正在跑的输出"一个独立的归属。

- 新增 `shellLive: StringBuilder` + `shellCommand/shellStartedAt/shellConvId`，
  onChunk 往里累积（不再只写 UI 状态）。
- `stopShell()` 落盘：`stdout = 已收到的全部输出`，`aborted = true`，
  `error = null`。中断不是失败，标红会让人以为命令出错。
- 新增 `shellGen` 世代号：停止时自增，正在跑的协程回来后发现号变了就闭嘴，
  否则它会把"取消"当成正常结束再记一条空记录（重复条目）。
- `openTerminal()` 切会话前先 `stopShell(keepOutput = true)`，
  否则旧会话的命令跑完会写进新会话的终端里。
- `clearTerminal()` 走 `stopShell(keepOutput = false)`——用户点的是清屏。

UI 侧配套：执行中的块改成和正式条目**同构**（左侧竖线 + 相同缩进），
按停之后它原地变成一条记录，看起来是"停下来"而不是"换了一块"。

### 二、输出带转义序列原样显示

`ls --color`、`git status`、`npm` 的输出都带 `\e[32m`，之前直接画出来是一串
`[32m` 方括号数字，看着像终端坏了。

新增 `data/AnsiText.kt`（纯 Kotlin，不引依赖）：

- 认 16 色（30-37 / 90-97）、粗体（1 / 22）、重置（0 / 39）。
- 组合参数按累积语义处理：`1;32` 是"粗体+绿"，不是两条独立指令；
  `22` 只关粗体、颜色留着。
- 非 SGR 的 CSI（`\e[2K` 清行、`\e[1A` 上移）吃掉——静态输出里没有意义。
- 被字符数上限截断在半路的序列整段丢弃，避免漏出半截方括号。
- `\r` 按"回行首覆盖写"处理（逐段覆盖，不是只留最后一段），
  进度条和 `ping` 这类重写同一行的输出不再叠成一长串。
- 复制/喂模型走 `Ansi.plain()`，剥掉转义。

### 三、长输出展开改分页

原来「点此展开」一次性铺开全部，`cat` 一个几万行的日志会一次布局几万行 → 卡死。
改成每点一次多给 400 行（`PAGE_LINES`），底部给「收起（回到尾部 18 行）」。

### 四、已知限制（这次没修）

中断只断开客户端的 HTTP 流，**沙箱里的进程会继续跑到 `SHELL_TIMEOUT`（300s）**。
要真正发 SIGINT 需要远端 pid，而 `process.Process/Start` 的流式帧里没有 pid，
拿不到就没法发信号。`ping` 这类死循环命令按停后仍会占用沙箱算力直到超时。

---

## 1.6.1（设置拆二级页；终端按手机终端的通行交互重做）

### 一、设置页拆成二级页

**起因**：设置所有内容排在一个 `LazyColumn` 里，九个分组、1244 行。改个主题要滚过
模型参数、签到、系统提示词、MCP 列表。用户直接问「能分二级页面吗」。

**做法**：包 `ui/screens/settings/`，首页只留入口与一行摘要：

| 入口 | 二级页内容 | 来源 |
|---|---|---|
| 账号与额度 | 账号列表、添加/刷新、积分签到、云端环境 | 原「账号」「积分与签到」「云端环境」 |
| 推理引擎 | 新会话默认引擎、进入自定义 API | 原「推理引擎」 |
| 自定义 API | 地址/Key/模型管理/请求参数/思考/上下文/系统提示词/测试连接 | 原「自定义 API」整块 |
| 外观与对话 | 主题、动态取色、折叠方式、思考展开、回车发送、渲染轮数、加载超时 | 原「外观」「对话」 |
| 技能与扩展 | 技能、连接器、MCP 服务器 | 原「技能」「MCP 服务器」 |
| 关于与更新 | 版本、检查更新、GitHub、反馈 | 原「关于」 |

- 路由是 `private sealed interface SettingsPage` + 一个 `when`，没有引入 navigation 库
  （只有一个 tab 内的层级，加依赖不划算）。
- `BackHandler` 在二级页拦返回键回首页：用户的心智是「退一层」，不是「关掉设置」。
- 每页的状态用 `remember { settings.xxx }` 自持。**前提是所有设置都即时写回**，
  所以离开页面丢状态不影响任何值；被丢的只有「拉取中」「测试结果」这类瞬时状态，
  重进重置反而是对的。
- 首页账号卡整卡可点进「账号与额度」；原来卡上的「刷新」按钮移到账号页的一行，
  功能没丢，也避免两处重复显示同一张卡。

### 二、终端界面

对照手机终端（Termux / ConnectBot）的通行交互重做，解决的都是实打实的摩擦：

1. **输入框改用 `TextFieldValue`**，快捷符号键插到**光标处**（原来只能追加到末尾）。
2. **快捷符号键条**：`/ - _ . | > & $ * " ' ~ # = :` 一排横向可滑，另加 `↑ ↓ ⌫`。
   手机上敲一个 `|` 要切两次键盘，这是终端输入最大的摩擦点。
3. **历史双向翻**：`↑` 取更早、`↓` 回更近，翻之前把已敲的内容存成草稿，
   回到末尾时还回去；手动改字后自动脱离历史。原实现只有一个「上一条」按钮，
   而且顺序是反的（先给最旧的一条）。
4. **长输出默认折叠**：stdout 超过 18 行只显示尾部，点「上方还有 N 行」展开。
   agent 一次 `cat` 能刷几百行，全铺开会把有用信息淹掉。
5. **可选中复制**：每条命令块包 `SelectionContainer`，另给「复制这条」小按钮。
6. **字号可调**：右上角 12/14/16/18 四档，存 `settings.terminalFontSp`（默认 14）。
7. **「最新」浮标**：上翻看历史时不再被新输出拽回去，但右下角给出「↓ 最新」回到底部。
8. **实时输出只渲染尾部 6000 字符**：`liveOutput` 每来一帧就变，排版全文会让整页掉帧。
9. 命令块加左侧竖线（agent 青色 / 手动绿色），末尾补完成时间，一眼分清一条命令的边界。

**测试**：221 项全绿；lint 0 error / 3 既有 warning。

---

## 1.6.0（模型管理：模型条目化 + 候选池）

**起因**：用户建议「模型管理可以去抄开源项目，比如 RikkaHub」。对照之后发现差距不在
界面好看与否，而在**数据形状**：

| 我们（1.5.6） | RikkaHub |
|---|---|
| 模型是 `List<String>`，只有 ID | 模型是对象，挂在 provider 下，带显示名/能力/模态/上下文 |
| 「拉取」直接覆盖这一个列表 | 拉取结果进选择器，勾选的才进「我的模型」 |
| 参数（思考强度、系统提示词、历史轮数）全局唯一 | 每个模型可覆盖，空值回落全局 |

后果很具体：`deepseek-reasoner` 会思考、`deepseek-chat` 不会，但两者只能共用一份
思考强度；用户把 ID 改成别名没地方存；在设置里删掉几个用不上的模型，点一次「拉取」
全回来了。

**改动**

1. `data/CustomModel.kt`（新）：模型条目 `id / name / reasoning / thinkingEffort /
   systemPrompt / contextTurns`。所有覆盖项为空（或 0）= 跟随全局，不在保存时把全局值抄进来。
   `guessReasoning()` 按 ID 猜初值（`reasoner` / `qwq` / `thinking` / `o3` …），只影响
   新加入时的标签，猜错能改。
2. `AppSettings`：`customModels` 从 `List<String>` 变 `List<CustomModel>`，同一 key 兼容旧
   格式（`decode` 同时认字符串数组与对象数组）；新增 `addCustomModels / removeCustomModel /
   updateCustomModel / customModelEntry`。`customModel()` 在组装配置时逐项回落：
   `entry?.thinkingEffort ?: 全局`。
3. `BuddyRepository`：`AppUiState` 拆成 `customModels`（我的模型）与
   `customCandidates`（候选池，只在内存）。`fetchCustomModels()` 只写候选池，不再覆盖
   我的模型、也不再自动改当前模型；新增 `addCustomModels / removeCustomModel /
   updateCustomModel / publishCustomModels`。
4. `ui/components/CustomModelManager.kt`（新）：我的模型列表（点行切换、编辑展开、删除）、
   候选池选择器（搜索 + 勾选；已加入的置灰不可重复勾）。拉取成功后自动弹出选择器——
   「拉取」的下一步必然是「挑几个」。
5. `ModelDialog`：本机模型按 `title` + ID 小字 + 「思考」标签渲染；本机分支彻底不再发
   `/models` 请求，入口统一指向设置页。空态文案改成「去设置里添加」。
6. 设置页原「模型 ID + 拉取」并排的一行，改为「当前模型 ID」单字段（手填仍然保留，
   自建端点常不提供 `/models`），拉取与添加归到「我的模型」标题行。

**没抄的部分与理由**

- **models.dev 目录 / 多 provider 分组 / 二维码导入导出**：我们只有一个用户自填端点，
  没有「十几家 provider 各配一套」的场景，加了只是把设置页变长。
- **模型级自定义请求头 / 请求体**：已有全局 `ThinkingInterceptor`，按模型再开一层会让
  「字段到底发没发」更难排查。
- **拖拽排序**：列表顺序按加入顺序，够用；拖拽要引入第三方 Compose 库，收益不抵依赖。

**测试**：新增 `CustomModelTest` 8 项（旧格式兼容、混合格式、去重、脏数据、编码回读、
思考模型猜测、关键词筛选、`customized` 判定），单测 221 项全绿；lint 0 error / 3 既有 warning。

---

## 1.5.6（自定义 API 的思考强度；弹窗不再自动拉模型列表）

**起因**：用户反馈「自定义 API 默认不思考」。查下来是两层原因叠在一起：

1. **从来没发过任何思考字段**。Koog 的 `LLMParams.additionalProperties` 在 0.6.0 里会被
   序列化成嵌套的 `additional_properties: {...}`（抓包确认），而服务端只认平铺在顶层的
   `thinking` / `enable_thinking` / `reasoning_effort`。
2. **「思考强度」本来就不是一个标准字段**。DeepSeek / 智谱 / Kimi 的开关是
   `thinking:{type:enabled}`，通义 / 硅基流动是 `enable_thinking: true`，
   OpenAI / xAI 才是 `reasoning_effort`。只发 `reasoning_effort` 的话，前两类直接当没看见。

### 做法

| 层 | 落点 |
|---|---|
| 档位 | `ThinkingEffort`：关闭 / 低 / 中 / 高，默认「中」（开箱就会想一步） |
| 写法 | `ThinkingStyle`：自动 / thinking / enable_thinking / reasoning_effort |
| 自动判定 | `ThinkingParams.autoStyle()` 按 host 猜：dashscope/aliyuncs→enable_thinking，deepseek/moonshot/bigmodel/volces→thinking，其余→reasoning_effort |
| 注入 | `ThinkingInterceptor` 挂在 OkHttp 引擎上，把字段并进 Chat Completions 请求体 |

**为什么挂在 OkHttp 而不是 ktor 的 client 钩子**：Koog 拿到我们传进去的 `HttpClient` 之后
会 `client.config { ... }` 派生一个新实例，`HttpSend` 的拦截器与 client 级 `onRequest`
在派生时都会丢（实测：钩子压根不触发）；只有**引擎配置**是共享的。
`LLMParams.additionalProperties` 也不能用——见上面第 1 条。

**几个边界**：

- `reasoning_effort` 这一套没有「关闭」的值，选关闭就**一个字都不发**：硬发 `none`
  在 o 系列以外的模型上容易换回 400。
- `thinking` 这一套**关闭必须显式发 disabled**：这家的思考默认是开的，不发等于「继续想」。
- `gpt-4o` / `gpt-4.1` / `gpt-4-turbo` 这类只支持普通对话的 OpenAI 模型收到
  `reasoning_effort` 是**报错**而不是忽略；默认强度是「中」，不挡一下的话第一次用就撞 400。
  所以在 EFFORT 写法下按模型名短路（`ThinkingParams.looksClassic`）。
- 思考模式开着时部分服务端会忽略 `temperature`（DeepSeek 明说），设置页已注明。

### 模型弹窗不再自动拉列表

打开自定义会话的模型弹窗时会顺手打一次用户的付费端点（`GET /models`），既慢又没必要——
模型很少变，设置页手动拉一次足够。现在弹窗只列 `ui.customModels`，空的时候给一句
「去设置里拉取」；云端会话的「重新加载」保持原样（那是免费的账号接口）。

### 踩到的坑

1. `HttpSend.intercept` 与 `createClientPlugin` 的钩子在 Koog 派生 client 后都不触发，
   表现是「改 body 的代码看着跑了，请求里什么都没有」——必须抓包才能发现。
2. `RequestBody.writeTo(buffer)` 会把原 body 消费掉，读完必须用读出来的内容重发，
   否则发出一个空 body（比不生效更难查）。
3. 单测必须真的抓一次请求体：字段名写错时服务端只会静默忽略，
   表现是「模型还是不想」，纯函数单测抓不到。

## 1.5.5（两套会话彻底分开；模型弹窗重做；自定义 API 补齐模型列表与系统提示词）

**起因**：自定义引擎的会话和云端会话共用一个「引擎」状态，切来切去就出问题——
一条会话可能前半段在云端跑、后半段在本机跑，两边的历史各有一半，谁也说不上完整。
再加上模型弹窗里那个「本机 · 自定义 API」切换卡 + 手填模型 ID 的输入框，
界面上一共有三种方式改变「这条会话到底谁来推理」，用户根本预期不了结果。

### 会话彻底分离

| 维度 | 云端会话 | 本机会话 |
|---|---|---|
| 推理在哪 | 云端 agent | 手机上的 Koog agent |
| 模型从哪来 | 云端 `session/set_model`（与官方 App / 网页端共享） | 用户自己端点上的模型 ID |
| 对话记录 | 云端，可跨端回放 | 只在本机 `AgentMemoryStore` |
| 花不花积分 | 花 | 不花（工具仍在同一沙箱里执行） |
| 列表标签 | 「云端」+ 星星图标 | 「本机」+ 终端图标 |

- 类型在**新建时定下**，之后不再互转。`useCustomEngine()` 只对草稿生效，
  对已有会话直接回一句「请新建一个会话」。1.5.4 留下的缝合会话仍按本地打开，
  本地历史为空时自动从云端补搬一次（`seedCustomHistory`），不至于变成空白。
- 归属判定收在 `AppSettings.kindOf()`：**没存过归属的一律算云端**。
  若回落到全局 `engineMode`，用户把默认引擎改成自定义之后，几十条老会话会被
  集体误判成本地——而它们的对话其实都在云端。
- `send` / `sendCustom` 互相兜底重定向：界面分流漏了一条路（比如重载后重发）
  就会把本机历史推进云端会话，这种错一次就够毁掉一条会话。

### 模型弹窗

- 删掉手填模型 ID 的输入框与「本机 · 自定义 API」切换卡；
- 弹窗按会话类型只显示自己那套模型：云端会话列云端模型，本机会话列
  `GET /models` 拉到的模型；
- 只有**草稿**会话顶部保留「云端 / 本机 API」两个 chip——不保留的话，
  默认引擎是云端时用户根本没有入口新建一条本机会话。

### 自定义 API 配置

- **拉取模型**：`GET {base}/models`，兼容 `{"data":[{"id"}]}` / `{"data":["id"]}` /
  顶层数组三种形状，字段认 `id` / `name` / `model`。拉到后点选即用（横向滑动），
  首次拉到且还没选过时自动选第一个。
- **系统提示词**：留空用内置默认；填了之后替换掉默认的「角色 + 回答风格」，
  沙箱环境与工具规则仍固定追加在末尾。用户自己贴了那一段就不重复追加。

### 关键取舍

| 点 | 做法 | 原因 |
|---|---|---|
| 会话类型能否改 | 建完就固定 | 换引擎等于换历史载体，缝在一起的会话删都不好删 |
| 本机模型 ID 存哪 | 与会话级云端模型**同一个 key** | 类型已互斥，两条命名空间不会同时被读 |
| 本机模型默认值 | 不回落到全局 `selectedModel` | 那是云端模型 ID，拿去请求第三方端点只会 404 |
| 沙箱规则 | 强制追加，不让用户覆盖 | 覆盖了模型就开始猜路径、用不存在的工具名 |

### 修掉「本机会话永远显示执行中」

真机截图：会话列表里一条「本机」会话旁边挂着「云端执行中」，永不消失。
两个原因叠在一起：

1. **服务端那边真的在跑**。自定义会话是用空 prompt 建的，而在创建请求里带上
   `prompt: ""` 会被服务端当成一次真实提问——这条会话没挂模型（自定义引擎只要沙箱），
   于是那一轮永远跑不完。现在空 prompt 时**整个字段都不发**，建完再后台补一次
   `session/cancel` 兜底（这一轮没人在等，cancel 是安全回收）。
2. **客户端还在拿云端角标**。本机会话在云端只有一条空壳记录，状态位要么是 null、
   要么是它当云会话时的残值。`refreshConversations` 与后台对账都改成：本机会话的
   状态只按「本进程里有没有它的 job 在跑」算（`Conversation.asLocal()`），
   后台对账对这类会话直接跳过（`StateReconciler` 无从下手，建临时连接重放空历史纯属浪费）。
   已存在的僵尸会话给一次清理机会，之后记进 `cleanedLocal` 不再碰。

顺带：列表里本机会话显示的模型改成它自己那套（云端记的是 null）。

### 踩到的坑

1. 模型弹窗里的列表来自 SharedPreferences 时不会触发重组——拉完列表弹窗还是空的。
   所以 `customModels` 要放进 `AppUiState`，不能只留在 `settings` 里。
2. `refreshModels()` 会用云端清单重新算 `selectedModel`，本机会话被扫到就会
   显示一个自己没用过的云端模型。加了 `isLocal` 短路。
3. 删会话只 `customAgents.drop()` 是不够的：本地历史躺在 prefs 里，
   谁也读不到、谁也删不掉，必须 `agentMemory.clear(id)`。

## 1.5.3（命令行窗口：能看见 agent 到底跑过什么）

**起因**：自定义引擎下推理在本机、执行在云沙箱，中间隔着模型。模型说「我已经装好了」
「文件已改」，用户没有任何独立渠道核实。这版把执行面直接摊开。

### 做了什么

- **终端页**（聊天页菜单 / 工作区顶栏进入）：命令提示 `$`、stdout 原样、stderr 橙色、
  退出码与耗时一行带出。agent 跑的标 `agent $`，手敲的标 `$`，颜色也不同。
- **手动执行**：输入框敲命令直接走 envd，**不经过模型、不消耗积分、不产生云端会话记录**。
  这是排查用途——模型说装好了，你自己 `ls` 一眼就知道真假。
- **实时输出**：`process.Process/Start` 本来是等命令跑完才返回全部内容，
  `npm install` 会黑屏几十秒。改成按帧流式读取，边跑边吐。
- **命令日志**：按会话存的环形缓冲，agent 的历史命令也在里面（切进终端就能看见）。
  单条输出首尾各留一半+标记截断，会话内最多 200 条。

### 关键取舍

| 点 | 做法 | 原因 |
|---|---|---|
| 流式 vs 一次性 | 只在终端用手动命令时开流式 | 工具调用是高频路径，多一层增量解析没收益 |
| 输出上限 | 首尾各半 + "省略 N 字符" | 只留尾丢报错开头，只留头丢最终结论 |
| 配色 | 固定深色，不跟明暗主题 | 终端就该是深的；浅底白等宽字极刺眼 |
| 入口 | 盖在主页之上的一层，不是 Tab | 云端会话也有沙箱，终端两种引擎都能用 |

### 踩到的坑

1. `source.request(5)` 返回 false 是 EOF，不是"没读满"，写成 `if (!request(5)) break`
   是对的，但用 `readFully` 会在断流时直接抛异常、把已收到的输出一起丢掉。
2. 帧头长度解错位后会连续读出垃圾（不报错，只是内容荒谬）。`len<=0 || len>8MB`
   必须当终止条件。单测里用"帧头声称 500 字节实际只给 3 字节"复现。
3. `CancellationException` 在 JVM 上是 `IllegalStateException` 的子类，
   `catch (e: Exception)` 会把它一起吞掉——用户点了中断，界面却一直"执行中"。
   容错 catch 必须先放行取消与 `InterruptedIOException`。

## 1.5.2（模型跟着会话走；自定义模式补齐附件与跨引擎历史）

**起因**：官方 App 里切换模型后网页端也会变，说明**模型是存在云端的会话属性**，
不是客户端的本地偏好。而 1.5.x 之前客户端只有一个全局 `selectedModel`，
导致两个连锁问题：

- 打开 B 会话，界面仍显示 A 会话选的模型（与云端实际模型不符）；
- 每轮发送前都按本地全局值 `set_model` 一次，**把用户在官方 App / 网页端刚设的模型改回去**。

**模型：以云端为准**

| 环节 | 改法 |
| --- | --- |
| 权威来源 | ACP `session/load` 响应的 `models.currentModelId`（`AcpModels.parse` 早已解析，只是被丢弃） |
| 缓存 | `acpCurrentModels[conversationId]` + `settings.modelFor(id)`，只用于首屏，ACP 一到就覆盖 |
| 切换 | `session/set_model`（服务端持久化）→ 成功才写缓存；失败回滚 UI 并提示 |
| 发送 | 不再调用 `set_model`；本轮模型随 `prompt` 参数下发，不动会话级设置 |
| 设置页 | `engineMode` / `selectedModel` 语义改为「**新会话**默认值」 |

**引擎：本地按会话存**

云端没有"本机推理"这个概念，所以引擎只能本地记（`conv_engine:<id>`，带账号前缀）。
`ui.engine` 取代原来的 `settings.engineMode` 参与发送分流：同一台手机上
A 会话走云端、B 会话走本机，互不影响。

**跨引擎历史（回答"WB 会话切到自定义会怎样"）**：

- 会话 history 在云端是 ACP 的，本地 agent 只认 `AgentMemory`。直接切过去模型会失忆，
  而且界面会突然变空白（`restoreCustomConversation` 读到空列表）。
- 现在切到自定义时先 `seedCustomHistory()`：ACP 回放一次，把 USER/ASSISTANT 消息
  （跳过思考与工具过程）写入本地 memory，再打 `markCustomConversation` 标记。
  本地已有历史时不覆盖（避免抹掉自定义阶段的消息）。
- 切回云端时明确提示：自定义那几轮只在本机，云端对话里看不到——这是机制决定的，
  不提示用户会以为消息丢了。

**附件终于进了沙箱**

原来自定义模式收到附件直接丢弃并提示。现在复用 `SandboxFs.write` 写到
`<cwd>/uploads/cb-<ts>/`（与云端链路同一落点），把路径清单拼进 prompt。
文本类附件额外给前 1500 字预览（`AttachmentPrompt`，纯函数 + 单测）：
模型不必先 `read_file` 就能判断"这是不是我要的文件"，小文件甚至不用再读一次。
二进制与图片只给路径——自定义端点不一定支持多模态，但沙箱里的文件它一定能读。

**验证**：170 项单测全绿（1.5.1 的 162 + 新增 8：`AttachmentPromptTest`），
`lint` 0 error / 3 warning。

---

## 1.5.1（真账号实测：把自定义链路从"能编译"推到"能用"）

**背景**：1.5.0 只是编译通过 + MockWebServer 单测。这一轮用真账号在**真实沙箱**上把
「建会话 → 拿沙箱 → envd 跑命令」整条链跑了一遍。按用户要求，测试专门新建了一个会话
（= 新沙箱），测完删除，账号原有 18 个会话/沙箱未动。

**实测结论（都写进了 AGENTS.md 的坑 22–25）**：

| 事项 | 结论 |
| --- | --- |
| 空 prompt 建会话 | `POST /console/as/conversations/` 只带 `prompt:""` → `code:0` |
| 不传 `model` | 同样成功，回包 `model: null`，沙箱照常分配 |
| 沙箱凭据 | 创建响应内联 `link / token / sandboxId / cwd / e2bDataEndpoint`，无需再查 |
| 沙箱环境 | Ubuntu 24.04.3 / Node 22.13.1 / Python 3.11.1 / git 2.43，root 身份 |
| 寻址 | `link` 用 conversationId（`65225-{id}` = ACP，`49983-{id}` = envd 数据面） |
| token 有效期 | `exp - iat = 259200`（**72 小时**），过期后 401/403 |
| 会话 = 沙箱 | 确认 1:1，测试时新建会话即新建沙箱，删除会话即释放 |

**据此修正的五处**：

1. **token 过期自动续期**。`SandboxExec.run` 新增 `onUnauthorized` 回调，401/403 时换新
   token 重试一次；`SandboxTools.token` 改成 `@Volatile var`，续期后就地更新。
   在此之前，隔天回来的旧会话会表现成「agent 突然不会用工具」，模型只看到一句
   `[error] HTTP 401`——因为 envd 的错误不抛异常，`withSandbox` 的 try/catch 兜不住。
2. **区分「命令非零退出」与「协议错误」**。实测 `exit 7` 会把 `error` 字段填成
   `"exit status 7"`，原实现把它当错误，和 `[exit 7]` 重复且误导模型去重试。
   `ProcessOutput.merge` 现在把 `exit status N` / `status N` / `signal:` 排除在 `error` 之外。
3. **自定义模式建会话不绑模型**。`createConversation(prompt, model: String?)`，传 null
   就不写 `model` 字段。会话只当沙箱载体，不在云端挂一个用不到的模型；
   同时直接用创建响应里内联的 session，省掉一次 `GET /session`。
4. **设置页可调参数 + 测试连接**。新增采样温度滑块、最大迭代、历史轮数，
   以及「测试连接」按钮：发一个 `max_tokens=1` 的最小请求，把 `401/403`（Key 错）、
   `404`（地址或模型 ID 错）、`402/429`（额度/限流）分别翻译成人话。
   地址输入框下方直接显示归一化后的真实请求地址（`EndpointResolver.previewPath`），
   填了 `/v1` 也不会拼成 `/v1/v1/chat/completions`。
5. **自定义会话标题兜底**。建完会话立刻 `rename` 成本地标题（取首句话前 24 字），
   否则会话列表里会堆一串空标题——自定义模式的会话在云端本来就是空的。

**验证**：155 项单测全绿（1.5.0 的 149 + 新增 6）。
- `SandboxExecTest`（新）：401 换 token 重试一次、续期失败只请求一次、
  无回调时 401 不重复请求；
- `ConnectFrameTest` 补：`exit status` 不进 `error`、`signal:`/裸 `status N` 形态、
  真实 end 错误仍保留。

**仍然未覆盖**：流式输出、附件入沙箱、release 混淆。

---

## 1.5.0（推理引擎二选一：云端模型 / 自定义 API）

**动机**：让用户能用自己的 OpenAI 兼容接口（DeepSeek 等）驱动任务，而不消耗账号积分。
定位没有变：任务仍然在官方云沙箱（`/workspace`）里执行，只是"谁在思考、谁在调工具"
这一层多了一个选项。

**两条路径并列，不是替换**。默认仍是 `ENGINE_WB`，行为与 1.4.10 完全一致；只有用户
在「设置 → 推理引擎」切到「自定义 API」后，发送才走本机 agent。没切的人感知不到任何变化。

| | 云端模型（默认） | 自定义 API |
| --- | --- | --- |
| 推理位置 | 云端 agent | 本机 Koog `AIAgent` |
| 模型 | 账号可选模型 | 用户填的任意 OpenAI 兼容端点 |
| 积分 | 扣 | 不扣 |
| 云端对话记录 | 有 | 无（只有会话本身） |
| 工具执行 | 云端 agent 内部 | envd `process.Process/Start`，仍在同一沙箱 |

**实现分层**（都遵守"纯函数解析 + 单测"的约定）：

- `data/ConnectFrame.kt`：Connect 帧编解码（`flags(1B)+len(4B 大端)+payload`），纯字节处理；
- `data/SandboxExec.kt`：`process.Process/Start` 调用与响应合并，`ProcessOutput` 是纯函数
  （base64 解 stdout/stderr、读 exitCode、容错截断帧与错误帧）；
- `data/SandboxTools.kt`：`run_shell` / `read_file` / `write_file` / `list_dir` 四个工具，
  统一走 `sh -c`；路径用 `ShellQuote` 做 POSIX 单引号转义（含单测）；
- `data/SessionAgent.kt`：每会话一个 agent + 独立历史 + 独立沙箱凭据，`SessionAgentHub`
  按 `conversationId` 缓存，端点变了就重建；
- `BuddyRepository.sendCustom()`：与 `send()` 并列，复用 `withSandbox()` 拿会话沙箱，
  但不调用 `session/prompt`。

**依赖变更**（这是本次最大的工程取舍）：

- Kotlin `2.0.21 → 2.2.10`，新增 `kotlin.plugin.serialization`（工具参数需要 `KSerializer`）；
- `ai.koog:agents-core:0.6.0` + `prompt-executor-llms:0.6.0`——**不引 `koog-agents` 全家桶**，
  那个还会带上 RAG / embeddings / MCP / tokenizer；也**不引 KSP**，工具用手写 `Tool` 子类注册；
- `io.ktor:ktor-client-okhttp:3.2.2` 作为 ktor 引擎，它依赖的正是项目已有的 okhttp 4.12.0。
- 版本选择依据：Koog 0.7.0+ 要求 Kotlin ≥2.3.10，会强制连带升级 Compose BOM 与 AGP，
  属于大改，故停在 0.6.0 / Kotlin 2.2.10 这条兼容线上。

**体积**：debug APK `17.0 → 21.4 MB`（+4.4 MB）。注意 release 也没开 `isMinifyEnabled`
（历史如此），所以正式包同样会带上这部分。要压回去需要给 ktor/serialization 写 keep 规则
再开混淆，那是独立改动，本次没做——没有真机验证的混淆规则比多 4 MB 危险得多。

**验证**：149 项单测全绿（原 135 + 新增 14）。其中 `SessionAgentLoopTest` 用 MockWebServer
起一个假的 OpenAI 端点，跑通「请求 → 解析 tool_call → 执行工具 → 回喂结果 → 收敛」整条链，
并断言第二次请求体里确实带上了工具返回。

**明确未覆盖**（装包前请知悉）：

- 附件在自定义模式下被忽略并提示，未做入沙箱；
- 没有流式输出，一轮结束后整段返回（Koog 的 `executeStreaming` 已具备，只是没接 UI）；
- `createConversation(prompt, model)` 是否会把 prompt 当成一轮发给云端，未在真机确认——
  若会，则新会话的第一条消息可能仍消耗一次性云端推理，需实测后决定是否改成建空会话；
- 混淆规则、release 包、真机全链路都需要在你的环境复核。

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

# 1.3.5 · 补丁：在途轮次按会话跟踪

1.3.4 用全局 `ui.sending` 判断「本地这一轮还有流在推」，但切到别的会话时它会被人为清掉、
切回来又没人置回，对账据此会误判：轻则该会话再不被对账（永远显示旧状态），
重则在任务正跑时触发补历史、把活的 SSE 掐断。

改为 `ConcurrentHashMap.newKeySet<String>()` 按会话记录在途轮次（`inFlightTurns`），
`send()` / `answerQuestion()` 进入时登记、`finally` 用真正的会话 id 注销（新会话的 id 在
`try` 内才产生，不能按 `activeId` 注销），`stop()` 主动清理。对账与补历史一律按会话查询。

验证：`verify.sh` 全绿，119 单测、lint 0、正式签名，
APK `dist/CloudBuddy-1.3.5-release.apk`（versionCode 19），
sha256 `647c9341579822536a6802980b30fb30b3c27229b0b1cdf911ff9efe87aaf510`。


# 1.3.6 · 修复「假运行」锁死：停止后仍提示云端在跑

## 现象
某个会话在客户端里**一直显示运行中**，输入栏是暂停按钮、发不出消息；点「结束」后
输入栏变回发送，但一发消息就弹「云端任务仍在进行，消息未发送」——而同一时刻用官方
网页端看，这个会话早就 `completed` 了。

## 线上核对（真账号，`batch-get`）
- 该会话：`status=completed`、`sessionStatus=completed`、`updatedAt` 停在完成后那一刻，
  创建到结束跨约 1 小时 33 分，之后一个多小时没有任何变动；
- 对照正在跑的会话：`status=working` 且 `updatedAt` 随每次活动前移 → **状态字段是实时的**；
- `lastActivityAt` 在 17 条会话上全部等于 `createdAt`（含正在跑的那条），是死字段；
  `currentVersionId` 恒为 0；`agentDirty` 会残留在旧会话上（`agentDirtyAt` 都停在
  11:02:00）。这三个都不能用来判断「有没有在跑」，客户端本来也没用它们。

结论：**不是云端滞后，是本地状态把自己锁死了。**

## 根因
`session/prompt` 的响应要等整轮跑完才回，本地是同步读这条 SSE。原先 `cancelTurn()`
只做两件事：发 `session/cancel` 通知 + `reducer.markStopped()`——**读线程仍阻塞在
`call.execute()` 上**，最长挂到 2 小时兜底（1.3.3 为修「10 分钟误判超时」加的那个）。
这期间：

1. `inFlightTurns` 里始终有该会话 → 对账 `decide()` 认为「本地在途优先」，一律返回
   `NONE`，云端 `completed` 永远采纳不进来 → 界面永久运行中；
2. `send()` 第一道闸看到 `state=RUNNING` 直接拒发（提示语还说是「云端」在跑）；
3. 用户点停止只改了角标，僵尸流随后推来任何一条事件又把状态推回 `RUNNING`。

## 改法
1. `AcpConnection` 记录本轮 `promptCall`；`cancelTurn()` 先置 `cancelRequested`，**直接
   `cancel()` 掉这条 Call** 打断阻塞读，再异步发 `session/cancel`（顺序反了会让读线程
   先抛错、把连接按故障关掉，通知反而发不出去）。
2. 传输层区分「用户停止」与「故障」：`cancelRequested` 为真时 `postRpc` 静默返回、
   不 `close()`；`rpc()` 在 `PROMPT` 等待前检查该标志抛 `TurnCancelled`，避免空等 2 小时。
   `prompt()`/上层把它当正常收尾，不标 `FAILED`。
3. `close()` 也置 `cancelRequested`：切会话、重建连接、对账接管都会关闭连接，都不该
   在界面上显示成红色失败。
4. 新增 `hasLiveTurn(id)`：`inFlightTurns` 有登记 **且** 该连接的 SSE 在 90 秒内还有事件
   （`AcpConnection.isStreamActive`，两个 `@Volatile` 字段比较，无额外开销）。对账、
   补历史、`send()` 闸门全部改用它——僵死的残留登记不再能永久挡住状态收敛。
5. `send()` 提示语改为「上一轮还没收尾」，不再把本地残留说成云端在跑。

## 性能
- 新增判断只是两个 volatile 读 + 一次减法和比较，对账频率未变（后台 0 / 推流 30s /
  有任务 10s / 空闲 45s）；
- `cancel()` 只在用户主动点停止时发生，正常路径零改动。

# 1.3.7 · 重装后仍显示「运行中」：滞留的 working 事件

## 新证据
用户重装 1.3.5 后，那个会话**仍然显示运行中、发不了消息**；同一时刻网页端显示已完成
且能正常发消息。用户随后在网页端发了一条消息，会话才恢复。

真账号复核（`batch-get`）：该会话 `status=completed`，`updatedAt` 从 14:45:53 跳到
15:25:35 ——正好是网页端那一发。说明云端记录一直是权威且正确的。

## 为什么 1.3.6 不够
重装会清空内存里的在途协程，1.3.6 修的「僵尸 prompt 流」在重装后不存在。
而 `finishReplay()` 会把历史里的 RUNNING 收敛成 IDLE，所以状态**也不是历史带出来的**。
剩下唯一来源：连接建立后，SSE 上滞留的旧 `session/update status=working` 事件
把 reducer 推成 RUNNING；旧任务早已结束，后面不会再有 DONE 事件来收尾。
对账把它改成 DONE，下一条滞留事件又推回 RUNNING。

## 改法
- `AcpReducer.allowRemoteRunning`：回放阶段允许采信历史状态；回放收尾后，只有
  **本客户端自己发起的那一轮**才允许 SSE 把状态改成 RUNNING/WAITING。
- `AcpConnection.setLocalTurnActive()`：Repository 在 `send()`/`answerQuestion()` 登记
  在途时置 true，`finally`/`close()` 置 false；`endReplay()` 按此决定回放后的开关。
- 别的设备发起的任务由 console 对账写入状态（10 秒一次），不再依赖可能过期的 SSE 事件。
- 新增单测 `stale working report cannot restart a finished turn`。

## 与 1.3.6 合起来的效果
- 状态角标：滞留 working 不再能把终态拉回运行中；对账是唯一权威。
- 发送：本地显示在跑但流已不活时不再拒发，交给 `batch-get` 做最终判断。
- 停止：点一次就能真正打断本地读，不再需要等 2 小时兜底。

# 1.3.8 · 抄 rikkahub 重做消息渲染：思考可点开展回 + 表格渲染

## 现象
1. 思考内容无法靠直接点击展开/收回（只能靠外层步骤组统一折叠，点标题行没反应）；
2. 模型输出的 Markdown 表格不渲染，被拍平成一串用空格隔开的纯文本。

## 根因
- `ThoughtRow` 的标题行只画了图标/文字/箭头，**没有 `.clickable`**——箭头是装饰，点了没任何动作。
- `parseMarkdown` 里 `|...|` 行只做 `split('|')` 后用空格拼成一段 `Paragraph`，没有表格块，也没有表格布局。

## 改法（整段抄 rikkahub，适配本项目的依赖）
- 新增 `ui/components/DataTable.kt`：照搬 rikkahub 的 SubcomposeLayout 两阶段测量表格——
  横向滚动、行内等高、列宽 min/max、按列/按单元格对齐、窄内容按比例拉伸铺满。
- 新增 `ui/components/ChainOfThought.kt`：照搬 rikkahub 的时间线步骤卡——整行 clickable、
  箭头翻转、`animateContentSize`、步骤多时折叠尾部。图标改用项目已有的 material-icons-extended
  （不引入 HugeIcons / bettertext 重依赖）。
- `ChatParts.kt` 的 `ThoughtRow` 换成 rikkahub 的三态（Collapsed / Preview / Expanded）：
  生成中默认 Preview（渐隐、限高 100dp、自动滚底），完成后可点标题收回/展开，带思考耗时；
  思考正文也走 MarkdownText，列表/代码/表格都能渲染。
- `Markdown.kt` 加真正的 `MdBlock.Table`：识别「表头行 + 分隔行」、解析 `:` 对齐、支持 `\|` 转义，
  用 DataTable 渲染，单元格内仍支持行内 Markdown（粗体/代码/链接）。孤立的 pipe 行退回行内渲染不丢字。
- 新增 6 个单测覆盖表格表头/数据/对齐/转义/段落分隔/行内样式。

## 性能
- 表格用两阶段测量，只在有表格时走 SubcomposeLayout；普通正文零开销。
- 思考的滚动渐隐只在 Preview 态启用，展开/收回后就是普通 Column。

# 1.3.9 · 折叠组统一动画 + rikkahub 输入框

## 现象
- 1.3.8 只给思考卡加了动画；「中间步骤组」（工具调用、过程叙述等被折叠的东西）展开收回仍是硬切换、箭头瞬间翻转，视觉不一致。
- 输入框样式简陋：方块发送按钮、无占位符、无边框层次。

## 改法
- `StepsGroup` 改成 rikkahub 式：整行点击、箭头旋转 180°、`animateContentSize()` 高度过渡、圆角 20dp、标题 titleSmall；展开状态仍由会话级 ViewModel 控制。
- `Composer` 照抄 rikkahub 的 ChatInput 结构：圆角 Surface + 1dp 半透明边框容器、透明 BasicTextField + 占位符「发消息给 WorkBuddy…」、附件 chip、模型 chip、圆形发送键（有内容 primary / 空禁用 / 运行中 error 停止三态），发送键带 fade+scale 进出动画，回车即发送。

## 注
Compose 编译器在增量缓存脏时会抛 `NotImplementedError: Unknown file`（ComposableTargetAnnotationsTransformer），`gradle clean` 后消失，非代码问题。

# 1.4.0 · 流式输出卡顿优化

## 现象
1.3.9 之后用户反馈「有点卡顿」，主要在流式输出（看着文字往外冒）时。

## 根因（流式高频重组链路上的四个叠加热点）
1. `ThoughtRow` 把高频变化的 `text` 当作 `LaunchedEffect` 的 key：每个 SSE 分片（60ms）
   都取消并重启协程，还重新启动一段 `animateScrollTo` 动画。
2. 思考耗时 `durationMs` 每秒写一次 state，状态挂在整卡共享的 `ReasoningState` 上，
   导致整个思考卡（含 MarkdownText、animateContentSize）每秒被拖着重组一次。
3. `StepsGroup` 外层与 `ChainOfThought` 内层**双层** `animateContentSize`：步骤组默认展开，
   正文/思考每增长一次就触发两轮高度测量+动画。
4. 快照推送间隔 60ms（~16fps），低端机上每帧全链路重组叠加渲染会掉帧。

## 改法
- 推送间隔 60→100ms（~10fps），打字机观感无差，重组频率降 40%。
- `ThoughtRow`：状态切换 effect 只用 `streaming/showThoughts` 做 key；自动滚底改用
  `rememberUpdatedState(text.length)` + `snapshotFlow.distinctUntilChanged()` + 120ms 节流，
  并用无动画 `scrollTo` 直接跳底。
- 思考耗时抽成独立 `ThinkingSeconds` 可组合项，自己持有计时状态，每秒只重组那行文字。
- `ChainOfThought` 新增 `animate` 参数；思考卡在生成中、步骤组在运行中关闭 `animateContentSize`，
  静止/完成后仍保留完整的展开收回动画（用户要的动画不丢）。

## 性能
- 状态机、对账频率、网络逻辑一律未动，纯渲染层改动。
- 展开收回动画在任务完成后完整保留；只在「内容持续增长的生成期」禁用高度动画。

# 1.4.1 · 会话永久「运行中」（第三种成因：服务端状态位滞留）

## 现象
用户报另一个会话（`Refactor CloudBuddy UI using open source designs`）又卡在「运行中」，
发不了消息。**新线索**：本机上还装着一个 1.3.3 的旧客户端，两个版本共存时——
旧客户端里这条会话也显示「运行中」，**点进这条会话就恢复正常，切回新客户端也正常了**。

## 结论（比前两版的判断更进一步）
前两版（1.3.6 / 1.3.7）都当成**客户端本地**状态没收敛来修：僵尸 prompt 流、滞留的
`working` 事件。这次的证据说明还有第三种、而且更根本的成因：

服务端的 `status` 不是从消息算出来的，而是「还有 agent 挂着这条会话」的存在标记。
agent 进程半路没了、连接被中间层吞掉，标记会一直挂在 `running` 上——**所有客户端读到的
都是同一个假状态，重装 App 也不会变**。唯一的清法是重新 `session/load` 一次这条会话，
也就是「点进去」这个动作本身。

所以「显示运行中 → 点进去就好了」不是巧合，而是这个状态位的收敛条件。

## 改法
1. **自愈**：列表里出现「服务端说在跑、本机没在跑、内容五分钟没动」的会话时，
   客户端主动建一条临时连接 `session/load` 一次就关，再回读真实角标（`scheduleHeal` /
   `healStaleRun`）。判定放在 `StateReconciler.shouldHeal`（纯函数 + 单测）：
   服务端 active、本机无在途轮次、内容 ≥5 分钟没更新、观察到在跑 ≥2 分钟、尝试次数 <3。
   一次只处理一条、同一条 60 秒冷却，避免去戳另一台设备真正在跑的会话。
2. **打开即收敛**：`reconcileAfterOpen` 拿到服务端记录后先过一遍 `settleIfStale`，
   打开会话就不会再看到输入栏变成「停止」。
3. **发送前收敛**：`confirmIdle` 在拒绝之前先自愈一次；清掉就直接发，并提示
   「已清理该会话滞留的运行状态」，不用再手动点停止。
4. **补掉两处闸门漏点**（前几版遗留）：
   - `setLocalTurnActive` 原本写 `allowRemoteRunning = active || replaying`，回放没收尾时
     等于闸门没关；改成只看本机这一轮。
   - `initializeAndLoad` 的 `loadDone = true; endReplay()` 移进 `finally`：`initialize` 抛
     非超时异常时，以前 `replaying` 会永远为 true，回放闸门永久失效。

## 边界
- 不改服务端语义、不新增接口（`session/cancel` 只在用户点停止时发）。
- 自愈是「只读地挂一下」，不写消息、不进连接缓存、失败静默。
- 单测 126 → 130（`shouldHeal` 四条判定）。

# 1.4.2 · 会话永久「运行中」（推翻 1.4.1 的因果，改为「不信任状态位」）

## 新证据（用户实测，比 1.4.1 的判断更硬）
1.4.1 上线前又拿到一条：**旧版 1.3.3 里「加载完历史消息」就正常了；而最初触发这条会话的
那个客户端，重进、刷新都好不了。**

这直接推翻了 1.4.1 的假设（「`session/load` 会把服务端状态位收敛掉」）：

- 1.3.3 里没有「打开后对账」，它加载完历史**只是把本地的 running 擦成 IDLE**，
  服务端 `status` 一个字都没变。所以「旧客户端点进就好了」证明不了服务端会收敛。
- 新版（1.4.0+）打开后会 `batch-get` 采纳远端，远端还挂着 running，于是重进多少次都还是
  「运行中」——这正是用户说的「重进、刷新都好不了」。

结论：服务端的这个运行标志**可能在会话结束后永远不自愈**（agent 进程没了、状态位没人清）。
把「等它收敛」当修复思路是错的，客户端必须自己掌握主动权。

## 改法（三层，逐级兜底）
1. **自愈升级**：临时连接 `session/load` 之后补一次 `session/cancel`（请求形式，等响应，
   `AcpConnection.resetRemoteTurn`）。这一轮在服务端早就没人等它了，cancel 是安全回收；
   只有带 id 的请求才能确认受理，通知形式服务端可以不处理。
2. **发送闸门降级**：`confirmIdle` 清不掉时不再永久拦截。判定「真在跑」只看两条硬证据——
   本地还在收事件、或内容在 5 分钟内更新过；两条都不成立就**放行**并提示
   「云端状态位仍显示运行中，已强制发送」。真在跑的话服务端会自己拒绝这条 prompt，
   错误照实显示，比把用户永久锁在门外好。
3. **手动自救入口**：会话卡片的更多菜单新增「解除卡住的运行状态」（仅当显示运行中时出现），
   走 `forceResetConversation`：load + cancel、清掉本地在途登记与旧连接、刷新列表。
   自动判定再保守也会有漏（内容小幅更新、尝试次数用尽），必须留一条 100% 能自救的路。

## 边界
- 自动自愈仍是「内容 5 分钟没动 + 本机无在途 + 无事件流」才动手，正常在跑的任务不会被误杀。
- 强制放行只发生在服务端状态位明确不可信时；本地有流或在途轮次时照旧拦截。
- 单测仍 130（状态判定逻辑未变，只改了收敛动作与闸门降级）。

# 1.4.3 · 大退重进后云端在跑、界面却显示空闲

## 现象
布置任务后大退 App，重新进入时消息在加载、新消息一条条往外冒，但右上角状态角标和
输入栏都显示「空闲」——不显示正在运行。

## 根因（和上一次那类「假运行中」正好相反，是「真运行中没被认」）
`AcpConnection.endReplay()` 里写的是 `allowRemoteRunning = localTurnActive`。
大退重进后这一轮不是本客户端发起的 → `localTurnActive = false` → 回放收尾把闸门关死；
之后 SSE 推来的**实时** `working` 事件在 `AcpReducer.updateState` 里被整道挡掉。

但消息分片（`agent_message_chunk`）不走这道闸门，所以出现「文字在流、状态是空闲」的错位。

这是 1.3.7 为修「重装后仍转圈」引入的错误假设：把闸门当成「这一轮是不是本客户端发起的」。
实际上历史状态的拦截早已由 `accept` 里的 `!history` 完成——回放期的 working 根本不会走到
`updateState`。实时阶段再按「谁发起的」拦截，只会误伤云端真在跑的任务。

## 改法
- `allowRemoteRunning` 语义改为「回放已收尾、接下来是实时事件」，回放结束后**一律放行**，
  不管是谁发起的那一轮：
  - `endReplay()`：`= true`（原来是 `localTurnActive`）
  - `setLocalTurnActive(active)`：只在 active 时确保打开，结束时不再关（关闸只发生在
    回放期与连接关闭）
  - `close()` 保持 `false`（连接已关，当然不采信）
- 保留两道真正的保护：`state == STOPPED` 时不被 working 拉回；回放期 `!history` 不采信历史状态。
- 新增回归测试 `live working after reopen lights up state without a local prompt`：
  不调用 `beginTurn` 模拟「不是本客户端发起」，实时 working 必须把状态点亮成 RUNNING。

## 与 1.4.1/1.4.2 的关系
那两版修的是**服务端状态位滞留**（假运行中）；这版修的是**本地闸门误挡**（真运行中）。
两者互相对冲：闸门太严会漏报，太松会误报。现在分工明确——回放期靠 `!history` 挡历史，
实时期全部采信，云端状态位不可信的问题交给对账 + 自愈 + 手动解除那三层兜底。

# 1.4.4 · 设置页手机号打码 / 下载进度与打开 / 文件类型分流

## 三个改动
1. **设置页手机号打码不一致**（截图反馈）：顶部账号卡副标题直接显示 `accountHint`
   完整号码，下面账号列表却显示 `189****4407`。同一串数字两种样，顶部改用
   `Account.maskHint()`，与列表统一。
2. **下载到本机加进度指示 + 完成弹窗「知道了」改「打开」**：
   - `SandboxFs.read` 新增可选 `onProgress`，流式读响应体、按 Content-Length 报
     0..1 字节进度（60ms 限频，避免高频重组；无长度报 -1 走不确定进度）；
     `readWorkspaceFile` 透传。普通预览传 null，仍走整块读取不增加拷贝。
   - 工作区下载时顶部横幅显示 `LinearProgressIndicator`；完成后按钮变「打开」，
     用 `Downloads.open()` 经 FileProvider + ACTION_VIEW 交给系统应用。
   - `file_paths.xml` 增加 `external-path` 授权公共 Download 目录（Android 7+
     直接给 `file://` 会抛 FileUriExposedException）。
   - 预览页下载按钮同样带转圈 + 「打开」。
3. **点击文件按类型分流**：`SandboxPath.canPreviewInApp()` 判定只有图片 / 文本 /
   HTML / Markdown / JSON 才进应用内预览；APK / PDF / Office / 压缩包等直接下载后
   跳转系统应用打开——不再让用户先进预览页看一句「该类型不支持预览」。

## 测试
- `SandboxPathTest` 新增 `only previewable files open in app`（5 支持 + 4 不支持）。

# 1.4.5 · 思考折叠开关 / 回车发送 / 反馈入口 / 下载取消与去重

## 四个改动
1. **「展开思考过程」开关重新生效**：1.3.8 抄 rikkahub 的 ChainOfThought 后，
   `ThoughtRow` 的 `LaunchedEffect` 在非流式分支只处理 Preview 状态——历史消息和
   已完成的思考卡初始就是 Collapsed，永远走不到设置分支，开关看起来「坏了」。
   现在两个方向都跟着设置走：开→历史/完成的思考卡自动展开；关→展开的收回。
   流式中的 Preview 行为不变。
2. **新增「回车发送消息」开关**（默认开）：关闭后输入框 `ImeAction.Default`，
   回车在多行输入框里就是换行，发送只认右下角按钮。设置存 `enter_send`。
3. **关于区新增「提交反馈」**：仓库链接下一行，打开 GitHub Issues。
4. **下载体验两处**：
   - 本次会话里已下载过的文件再点击时直接 `Downloads.open`，不重复下载；
   - 下载气泡里「知道了」换成「取消」：`SandboxFs.read` 加 `isCancelled` 标记，
     流式读取循环里检测到就抛出中止（穿透 `getOrDefault` 的吞异常），气泡显示
     「已取消下载」。下载中按钮为「取消」，完成后仍为「打开」。

# 1.4.6 · 执行中的折叠组动画 + 删掉「展开/收起」小字

- **动画不是 bug，是 1.4.0 修卡顿时的临时开关**：当时生成中每条分片都改内容高度，
  `animateContentSize` 会持续触发测量动画；于是把执行中的步骤折叠组改成硬切换。
  卡顿主因（双层动画叠加、分片高频重组）此后已逐项修掉，现在把高度动画全程
  开回来——执行中展开/折叠同样平滑过渡。
- 会话卡右侧的「展开 / 收起」小字删除：箭头旋转已经表达了状态，不再重复。

# 1.4.7 · 角标提前跳「已完成」、工具卡却还在转

## 现象（用户实测，已修正归因）
任务就是在 App 里发起的，用户一直停在会话页。agent 等一个几分钟的后台工具调用期间，
用户切走再回来：右上角标已变成「已完成」，工具卡「正在 Get Task Output」
却还在转圈，界面像卡住。重进会话后恢复正常，云端任务从头到尾没停过。

## 根因
1.4.3 起（修「真运行中没被认」）回放收尾后状态上报一律采信。旧 `updateState` 只挡了
IDLE（`turnActive` 时降级返回），**没挡 DONE**——所以即使这轮就是本客户端发起的、
`turnActive=true`，一条中途的 `sessionStatus: completed/idle` 上报照样把角标顶成
「已完成」，还顺带清掉 turnActive；而工具卡只由 `tool_call_update` 驱动，还在 RUNNING。
两条信息来源一错位，就是这个画面。是否旁观、谁发起都不是关键。

## 改法
- `AcpReducer.updateState`：**还有工具在转圈时，状态上报里的 DONE/IDLE 不采信**。
  判定只看工具卡状态，与谁发起无关，本地发起的轮次同样受保护。真正的收尾只认
  `session/endTurn` 与 prompt 响应（`finishTurn`），它们不走这条路径，
  所以拦掉的只会是别的来源的中间态上报。
- `session/endTurn` 收尾时顺带把还在转圈的工具卡清掉（与 `finishReplay` 同款），
  防止「角标已完成、步骤还在转」的残留错位。
- 新增两条回归测试：有工具 RUNNING 时 completed 上报被忽略（收尾后照常采信）；
  endTurn 清理残留转圈的工具卡。132 → 134。

## 与历次修复的关系
1.4.3 修「真运行中没被认」，这版补上它的对偶边界：采信实时事件不等于采信
**任何时刻**的终态上报——工具还在转时，终态必须等到正式收尾信号。

# 1.4.8 · 补上 1.4.7 守卫的静默间隙漏洞

1.4.7 的守卫按「有 RUNNING 工具」拦截终态上报。但如果假 completed 恰好在
**两个工具之间的静默间隙**到来（agent 思考几分钟、还没调下一个工具，
没有工具在转圈），旧判定拦不住，角标还是会错。

补充判定：本客户端这一轮还在跑（`turnActive`）时同样不采信 DONE/IDLE 上报。
本地轮次的真正收尾走 `session/endTurn` 与 prompt 响应（`finishTurn`），
都不经过状态上报这条路径，因此不会误伤正常收尾。

两条路径现在合起来覆盖：
- 本地发起的轮次：`turnActive` 挡住整个执行期；
- 旁观/另一台设备驱动的轮次：`hasRunningTool` 挡住有工具可见的执行期；
  纯文本流式期间若真收到假 completed，下一条 working 事件会把角标拉回来
  （1.4.3 起 allowRemoteRunning 对实时事件全开），不会永久错。

新增回归测试：本地轮次静默间隙的 completed 被忽略、finishTurn 照常收尾。134 → 135。

# 1.4.9 · 已下载文件跨重启去重

1.4.5 的「点击已下载文件直接打开」只记在会话内存里：App 重启后记录清空，
再点同一个文件又会重新下载——用户实测仍然重复下载。

改法：`Downloads.findExisting(name)` 直接查公共 Download 目录有没有同名文件
（0 字节的坏文件不算），命中就构造 Saved 直接 `open`。判定落在本机文件系统上，
重启后依然生效。点击优先级：本次会话下载过的 → 本机已有同名 → 都没有才下载。

# 1.4.10 · 折叠组滑动/展开卡顿

用户反馈各列表滑动卡，定位后集中在折叠处（Markdown 反而不卡）。三处开销：

1. **双层高度动画**（1.4.6 引回）：StepsGroup 外壳全程 `animateContentSize`，
   组内每张思考卡的 ChainOfThought 还有自己一层——展开/收起时两层同时逐帧
   重测量，正是 1.4.0 定位过的卡顿源被重新叠回。改为组内思考卡一律不再自带
   动画（ThoughtRow 加 animate 参数，组内传 false），高度变化由外壳统一动画，
   观感不变、测量只做一层。
2. **工作区每行常驻组合 DropdownMenu**：折叠态也走组合，长列表滚动变沉。
   改为 `if (menu)` 展开时才组合。
3. **LazyColumn 缺 contentType**：聊天与工作区列表加同类型复用提示，
   滑动时同类项复用组合缓存。

# 1.4.11 · 工具调用中刷新/重进 → 角标跳「已完成」且后续消息刷不出来

## 现象
工具调用进行中刷新或重进会话，角标直接变成「已完成」，而且此后刷新、重进都看不到
后面产生的消息。云端任务实际一直在跑。

## 根因（两处叠加，都在「回放收尾」这一瞬）
1. **`finishReplay()` 把「这轮还没收尾」的事实擦掉了**。为了让旧会话不一直转圈，回放
   收尾会把历史里 `running` 的工具卡改成 NONE，于是 `turnActive` 与 `hasRunningTool()`
   双双为假。此时服务端没有 prompt 在途，随手一条 `completed` 状态上报就通过
   `updateState` 把状态写成 DONE——这正是「跳已完成」。
2. **状态判成终态后，补历史的闸门也关了**。`scheduleResync` 只在 `updatedAt` 前进时
   才补，而 agent 卡在工具里时这条记录本来就不动；`reconcile` 又因为本地/远端都
   「非活跃」不再触发重放。服务端的实时事件只推给有 prompt 在途的连接，重进后的
   那条连接早就静默了——于是后续消息永远刷不出来。

## 改法
1. **记住「悬在工具上的一轮」**：`AcpReducer.openTurn`——回放收尾时若历史**最后一条**
   是还在转圈的工具调用，就记下来，并把状态保留为 RUNNING（而不是收敛成空闲）。
   工具卡照旧擦成 NONE（不转圈），但事实留着。
   - `updateState` 的终态守卫扩为 `turnActive || hasRunningTool() || openTurn`；
   - 正式收尾信号（`session/endTurn`、prompt 响应、用户停止、新一轮开始）才清。
2. **对账层拒绝这一刻的终态**：`reconcile` / `reconcileAfterOpen` 在 `openTurn` 且远端
   报终态时 `Action.NONE`，不再把角标写成「已完成」；远端真说 running 则照常采纳。
3. **补历史不再只看 `updatedAt`**：`scheduleResync` 新增「本地静默兜底」——本地这条
   连接已经没事件（`streamAlive == false`）且（远端在跑 或 `openTurn`）时，按原有退避
   （8s→…→60s）定期重放一次历史。打开会话时也立刻补一次，不用干等对账周期。
   反过来，本地连接还在收事件时**绝不重建**——重建会掐断实时流，历史还会覆盖正在流式
   的内容（这也是一类「消息卡住」的来源）。
4. **自愈不再误杀**：`settleIfStale` 遇到 `openTurn` 直接放弃自愈。否则「长工具调用 +
   内容 5 分钟没更新」会命中自愈条件，load + cancel 把用户真在跑的任务砍掉。

## 测试
- 新增 2 条：历史停在未完成工具时回放保留 RUNNING、且 completed 上报被忽略
  （endTurn 后照常收尾）；历史停在回答上时不被误判成「悬空」。
- 135 → 137。

# 1.5.0 · 本机会话照 RikkaHub 重做：消息流持久化 + 本机技能/MCP/提示词

## 现象（用户反馈）
1. 本地 API 会话消息有时消失：退出再进，提问和回复都没了。
2. 思考强度调成中，仍看不到思考内容。
3. 地址填 `https://xxx/v1` 请求 404。
4. Skill / MCP 只对云端模型生效，给自定义 API 用的设置没有；设置页排布不科学。

## 根因
1. 历史只在整轮成功结束时写盘，中途退出 = 什么都没存；`AgentTurn` 只存「提问+回答」，
   思考与工具过程根本不在数据模型里。
2. Koog 的 `AIAgent.run()` 只回最终文本，`Message.Reasoning` 在框架内部被丢弃——
   不是参数没配对。
3. ktor 对 `chatCompletionsPath` 的相对路径处理是替换 base 的最后一段，
   base 带 `/v1` 时拼出 `/chat/completions` → 404。
4. 本机会话没有技能/MCP/提示词通道；设置页按「设置类型」分组而不是按「能力归属」。

## 改法
1. **SessionStore**：一个会话一个 JSON，整条消息流原子落盘；先落盘再执行，失败与中断
   留痕；`sealInterrupted` 收尾进程被杀的残局（`runing` 工具 → 失败+「已中断」，
   悬空提问 → 补说明）。测试过程中修出两个真竞态：
   - `clear()` 异步删文件，删后立刻读会把消息流从磁盘加载回来 → 删除改同步；
   - 排队中的写盘任务在 clear 之后把旧内容写回去（快照先取、写盘后到）→
     落盘代数 +1，写任务带代数、写前对不上就作废（可控执行器的确定性回归测试）。
2. **ReasoningTee**：装饰 PromptExecutor，转发时抄出 reasoning 流回调写入 THOUGHT 消息。
3. **EndpointResolver**：base 只留 `scheme://host:port`，前缀整段进 path。
4. **本机三件套**：PromptPreset（片段 + 位置 + 优先级，零片段时系统提示逐字不变）、
   LocalSkillStore（目录式 SKILL.md + frontmatter + use_skill 渐进披露 + 路径逃逸拦截，
   修过 canonicalFile 对不存在目录照样通过的漏洞）、LocalMcp（url 型桥进 ToolRegistry，
   失败降级）；MCP 卡片双作用域开关。
5. **设置页**：首页六栏（账号与额度 / 会话引擎 / 云端会话 / 本机模型 / 通用 / 其他），
   分栏主线是能力归属。
6. **应用内更新适配三包**：`selectAsset` 按档位（EDITION_NAME）挑 APK 资产，选错包
   等于静默换档；共存版与另两包同一把正式签名，取消「plus 不检查更新」的旧限制。

## 测试
- 新增：SessionStore（12+竞态回归）、TranscriptMemory、PromptComposer、SkillFrontmatter、
  LocalSkillStore、UpdateChecker 三包资产选择（12）等。
- 876 → 951，三变体 lint 0 error。

# 1.5.1 · 本机会话历史消息再次消失（云端同步通道没关干净）

## 现象（用户反馈）
自定义 API 会话的历史消息仍会消失；用户猜测「是不是又在这些会话里关闭了云端同步」。

## 根因
本机会话在云端是空壳（故意不传 prompt），但仓储里多条路径仍会为它建 ACP 连接：
1. `seedCustomHistory` 走 `connection()`——那条连接带着 `onUpdate → publish` 进缓存，
   之后云端任何事件推送都会用空快照把本机消息整份覆盖掉；
2. `publishModels` 会把云端模型 ID 写进本机会话的模型设置；
3. `silentResync` 对本机会话执行的「补历史」语义就是「替换为云端内容（空）」；
4. 引擎归属记录按账号前缀存在 SharedPreferences，换账号后 `kindOf` 回落成「云端」，
   会话被当成云会话打开——ACP 回放空历史，界面上就是「消息全没了」。

## 改法（三层）
1. **出口拦截**：`publish` / `publishModels` / `silentResync` 见本机会话直接返回。
2. **入口拦截**：`connection()` 对本机会话 `check` 报错；历史迁移改用临时连接用完即关。
3. **判定自愈**：新增 `isLocalConv()`——归属记录是 CUSTOM ‖ 在自定义标记表 ‖
   SessionStore 有内容，满足其一即本机；`openConversation` 恢复、`send` 分流、
   对账、自愈、模型推送全部换用，记录丢失时自动改回 CUSTOM。
4. `restoreCustomConversation` 补 `activeId` 守卫（恢复期间切走不再串台）。

## 测试
- 951 项全绿（base/full/plus），lint 0 error。

# 1.6.0 开发中 · 思考显示自研引擎重做 + 多提供商模型管理

## 现象（用户反馈）
1. 自定义 API 会话依旧不显示思考；要求照搬 RikkaHub 的会话框架，不要再局限于云端会话那套。
2. 模型管理没有照搬：只能配一个模型提供商。
3. 设置页排版也要抄 RikkaHub。

## 根因
1. **Koog 0.6.0 根本不解析 `reasoning_content`**——在它的 OpenAI 客户端 jar 里搜不到这个
   字符串（实测）。DeepSeek 及大量中转站的思考内容正是用这个字段流式下发的，
   所以上一轮的 ReasoningTee 建立在错误前提上：Koog 上游就没收到过思考。
2. 单一提供商：`customBaseUrl/customApiKey` 是全局一对，所有模型挤在同一个端点下。

## 改法
1. **自研流式引擎**（照 RikkaHub 协议层做法，彻底脱离 Koog agent 框架）：
   - `OpenAiStreamClient`：ktor + SSE 手工解析，`reasoning_content ?: reasoning`
     双字段兜底（DeepSeek / OpenRouter 写法都接住），content / tool_calls 分片
     （按 index 拼装）/ finish_reason 全拿到；请求体自己拼，思考参数直接平铺，
     拆掉 OkHttp 拦截器补丁。
   - 工具循环自实现：assistant tool_calls → 执行 → tool 结果回传 → 再问，
     直到最终回答或达最大迭代；工具失败转错误文本回给模型。
   - Koog 工具表通过 `asChatTool()` 适配（descriptor→JSON Schema，decodeArgs 执行），
     沙箱 / 技能 / MCP 工具实现零改动。
   - **流式显示**：仓储新增 pushStreamLocal / appendStreamLocal / endStreamLocal，
     思考与回答逐字进界面（打字机效果），定稿才落盘；中断/失败路径把半截内容
     收口落盘，不悬在「打字中」。
   - 修一个真 bug：agent 收尾 remember 的回答与流式落盘的回答重复（中间隔着思考
     消息，连续去重拦不住）→ TranscriptMemory 增加本轮内去重，跨轮不误杀。
   - ReasoningTee / CustomLlmClient（Koog 客户端 + 拦截器）整体删除。
2. **多提供商**（照 RikkaHub ProviderSetting 结构）：
   - `LlmProvider(id/name/baseUrl/apiKey/enabled)` 实体；模型挂提供商下，
     选择键 `providerId/modelId` 全局唯一，不同端点同名模型不串台。
   - 旧数据迁移：全局端点自动搬成第一个提供商（幂等），旧字段保留兜底。
   - 设置页新增「提供商」卡：增改删、启用开关、逐提供商测试连接；
     模型拉取按提供商进行；候选池切提供商即清空。
   - 聊天页模型弹窗按提供商分组（RikkaHub ModelListSheet 做法），顶栏显示模型名。
   - 手填模型 ID 入口移进候选框（不提供 /models 的自建 vLLM 仍可用）。

## 测试
- 新增 ChatStreamTest（SSE 解析 6 形状）、流式回答去重回归等；963 项全绿（+12），
  三变体 lint 0 error。

# 1.6.0 追加 · 模型列表在提供商详情里拉（照 RikkaHub DetailPage）

## 现象（用户反馈）
1. 还是拉不到模型列表：「人家模型列表是在提供商设置里面拉的」。
2. 设置项只搬了一点过来，「人家设置项里很多东西」。

## 根因
上一轮的提供商只是个编辑弹窗（名称/地址/Key/测试），模型管理仍是全局一张卡 +
手动选提供商 + 手填 ID——拉取动作和提供商是分离的，这正是 RikkaHub 之前被否掉的形状。

## 改法
1. **提供商详情页**（`ui/components/ProviderManager.kt` 重写，仿 SettingProviderDetailPage）：
   - 列表页只留「看 + 入口」：卡片显示地址预览、启用/停用、模型数标签，点击进详情；
     「添加」即建空壳并跳详情（照 AddButton 的做法）。
   - 详情页分「配置 / 模型」两块：进入**自动**拉一次该提供商的 `/models`
     （地址或 Key 改动即重拉，右上可手动刷新），失败保留旧清单并显示错误口径
     （catalog 的 HTTP 状态描述，与测试连接同通道）。
   - 「从列表添加」弹窗：搜索过滤、逐条 +/✓、全部加入（N）、已加入的标「已加入」不可重复勾；
     端点不提供 /models 时走「手动添加」。
   - 已加入的模型一行一卡，展开编辑显示名 / 思考开关 / 思考强度 / 历史轮数 / 系统提示词
     （原 CustomModelManager.ModelRow 原样迁移）；当前会话选中的模型标 ✓。
2. **补齐 RikkaHub 详情页的设置项**：
   - `chatPath`（自定义请求路径）：留空走 EndpointResolver 统一规则，http(s) 开头当完整 URL；
     `CustomModelConfig.streamUrl` 统一算 URL，`OpenAiStreamClient.stream()` 用之。
   - `headers`（自定义请求头）：多行 `Key: Value` 编辑，随请求逐条发送
     （中转站的 HTTP-Referer / X-Title 这类归属头有了落点）。
   - Key 输入框加显隐切换（照 secret textFieldItem）。
3. 删除全局「模型」卡与 `CustomModelManager.kt`：模型管理只存在于提供商详情，
   候选池机制（内存态）随之退役；设置页的「本机模型」页只留提供商列表 + 全局请求参数。
4. 「本机模型」页内加三级导航（detailProviderId + BackHandler），返回键回列表不退出设置。

## 测试
- 新增 LlmProviderTest：encode/decode 往返（含 chatPath/headers）、旧数据 decode 兼容、
  请求头解析与文本化、streamUrl 三种拼法；全绿（full），base flavor 编译通过，lint 0 error。

# 1.6.0 追加 · 提供商界面的小屏适配（410×502 矩形手表）

## 现象（用户反馈）
用户要在 410×502 的矩形手表上用；已有界面都没问题，只有提供商那一块可能出问题
（RikkaHub 自己的提供商设置在小屏上也有显示问题）。

## 风险点与改法（全部限定在 ProviderManager.kt）
小屏在常见密度（1.5~2.0）下只有 205~273dp 宽、251~335dp 高，手机 ≥360dp 宽。
适配不是二值化布局，只处理两类必炸问题：

1. **M3 默认 AlertDialog 有 280dp 起的最小宽度**：在表上内容被 clamp 贴边、
   两按钮挤变形。四个弹窗（列表页删除确认、详情页删除确认、手动添加、模型选择）
   全部改走 `BasicAlertDialog(usePlatformDefaultWidth=false)` 自控：
   满宽 + 安全边距（小屏 10dp，手机 24dp）+ widthIn(max=520)。
   抽了 `AdaptiveDialog` 骨架（标题/内容/确认取消行自绘），行为与 M3 一致。
2. **固定高度弹窗列表撑爆屏高**：模型选择弹窗原 `heightIn(max=320.dp)` 在
   335dp 高的表上直接把按钮顶出屏幕。改为弹窗最高占屏高九成，
   模型列表 `weight(1f)` 吃剩余高度（矮屏自动缩、仍可滚动），
   搜索框、全部加入、确认按钮行任何屏高都可见。
3. **详情页标题行按钮**：小屏时「从列表添加」TextButton 收成 + 图标
   IconButton（四个字带图标在 200dp 级宽度上会把标题挤没）；
   详情页内边距 14dp → 小屏 8dp。
4. 其余布局（weight + fillMaxWidth + horizontalScroll 的强度 chips）
   天然随宽度缩，不动。

## 测试
- 编译/单测/lint 全绿；full debug 包 wip3 出到 dist 供真机验证。

# 1.6.0 追加 · 模型级能力设置（工具调用 / 读图，照 RikkaHub ModelAbility/Modality）

## 现象（用户反馈）
「每个模型自己的设置也没抄好：能否工具调用、能否读图，这些都需要设置。」

## RikkaHub 的形状
Model 编辑卡：modelId / displayName / ModelType(CHAT/IMAGE/EMBEDDING) /
inputModalities(TEXT,IMAGE) / outputModalities / abilities(TOOL,REASONING) /
contextLength / customHeaders / customBodies / 内置工具。

## 我们的对应实现（引擎只做 Chat Completions，跳过类型与内置工具）
1. `CustomModel` 加 `tools: Boolean = true`（能力 TOOL）、`vision: Boolean = false`
   （输入模态 IMAGE）。encode 只在偏离默认值时落盘，旧数据解析天然取默认。
   `guessVision(id)`：`-vl` / vision / glm-4v / 4o / omni / gemini / claude 猜 true
   （宁可漏判：没标读图的发图走沙箱兜底，标错的端点直接报错）。
2. `CustomModelConfig` 加 enableTools / enableVision，`customModel()` 按模型条目带出。
3. 引擎生效：
   - `enableTools=false` → 工具表整表不下发（SessionAgent）。不支持 function calling
     的端点收到 tools 会 400 或编造工具名，纯聊天模型也不该被引诱。
   - `ChatMsg.User` 加 `images`：非空时 content 变 `[{type:text},{type:image_url}]`
     数组（OpenAI 兼容标准形状）。`sendCustom` 里读图开着的模型把 image/* 附件转
     data URL 直发；没开的保持旧路径（图片在沙箱，模型按路径用工具读）。
     历史轮不回放图片，长期引用的图片证据走沙箱文件。
4. 模型编辑卡（提供商详情页展开区）加「能力」区：工具调用 / 读图两个开关；
   模型行副标题加「读图」「无工具」标签（照 RikkaHub 模型卡的能力标签）。
5. 顺手删了 ChatStream 里类内重复的 toJson 死代码（真正生效的在 ChatRequest）。

## 测试
- ChatStreamTest +3：多模态 content 数组形状、无图保持纯文本、关工具不下发 tools；
- CustomModelTest +2：tools/vision 往返、guessVision；
- full/base 编译、单测、lint 全绿。

# 1.6.0 追加 · 品牌图标与模型目录自动判断（照 RikkaHub 的 AutoAIIcon / ModelsDevCatalog）

## 现象（用户反馈）
「图标也能抄一下，而且它好像还有自动判断一些模型设置的功能。」

## RikkaHub 的机制（源码确认）
1. 图标两条线：模型按名称正则匹配 lobehub 品牌图标集（computeAIIconByName），
   提供商按 baseUrl host 取 favicone.com 的站点 favicon（Favicon 组件）。
2. 自动判断：内置 models.dev（https://models.dev/api.json，1 万余条模型记录）
   快照 + 远程更新（ModelsDevUpdater），按完整 id / 去前缀 / canonical id 多键索引、
   多数投票合并，提供输入模态 / 工具 / 思考 / 上下文长度；查不到再走内置 token 规则。

## 许可处理
RikkaHub 本体是 AGPL-3.0，不能复制代码；改为自己实现同机制。资产来自
lobehub icons（MIT，unpkg @lobehub/icons-static-svg）与 models.dev 公开 API。

## 实现
1. **数据层 `ModelRegistry`**（object）：
   - assets 内置 `models_dev_slim.json` 快照（5481 键 / 217KB，投票合并 + 压平后的
     索引，格式 `键 -> [读图入,读图出,工具,思考,上下文K]`）；
   - `find(id)` 查找键逐级放宽：完整 id → 去 `:` 渠道后缀（OpenRouter `:free`）→
     去提供商前缀 → 再去后缀；大小写不敏感；
   - `buildFromApiJson(raw)` 纯函数：在线拉 api.json（6.7MB）→ 同规则投票合并 →
     生成快照文本，`installRaw` 热替换 + 落 `filesDir/models_dev_slim.json`
     （下次启动优先于 assets）；repo `refreshModelCatalog()` 24h 节流、静默失败，
     进提供商详情页时触发。
2. **图标 `ui/components/BrandIcon.kt`**：
   - `BrandIcons.key(text)`：36 个品牌键的正则匹配（模型 ID / 提供商名 / 域名通吃，
     具体品牌先于泛匹配），单测覆盖常见模型与中文名；
   - `BrandIcon`：命中显示 lobehub 图标 PNG（36 个，96px，assets 内联进
     drawable-nodpi），否则字母头像（首字母 + 哈希底色）；
   - `ProviderIcon`：favicone.com 按 host 取站点真实 logo（内存缓存按 host，
     失败/超时回落 BrandIcon）——列表卡、详情页名称字段都在用。
3. **生效点**：提供商列表卡 + 详情页名称字段（ProviderIcon）、已加入模型行
   （BrandIcon + 目录查到的上下文长度标签「200K」）、远端选择弹窗行（BrandIcon）、
   聊天页模型选择器（BrandIcon 28dp）；`addCustomModels` 的能力初值改为
   **目录优先、启发式兜底**（tools/vision/reasoning 三项都查目录）。

## 测试
- ModelRegistryTest 4 条：快照解析查找、查找键放宽（前缀/:free 后缀）、
  投票合并（2/3 多数、上下文取唯一记录）、坏数据静默拒绝；
- BrandIconsTest 4 条：常见模型 ID、中文提供商名、未命中 null、具体品牌优先；
- full/base 编译、单测、lint 全绿。

# 1.6.0 追加 · 模型能力指示图标（照 RikkaHub 的 ModelModalityTag / ModelAbilityTag）

## 现象（用户反馈）
「这也没显示出 ds 图标，其实我没要你抄提供商和模型图标，我说的是模型能力指示图标。」

## 改法
1. **撤掉上一轮加的品牌图标**（lobehub 品牌图 PNG、favicon、字母头像组件全删）——
   误解了需求；models.dev 目录与能力自动判断保留（那部分是对的）。
2. 新增 `ui/components/AbilityTags.kt`，照 RikkaHub 模型卡的标签结构：
   - 模态标签：输入图标（文本，读图模型多一个图片图标）→ 箭头 → 输出文本图标；
   - 工具标签：扳手（tertiaryContainer 底）；思考标签：灯泡（primaryContainer 底）；
   - 上下文标签：目录查到的长度（200K）。
   两种形态：带彩色底的 chips（模型卡/聊天选择器）、裸小图标 + K 数文本（选择弹窗行内）。
3. 生效点：提供商详情页模型行（能力一目了然，副标题只剩 ID 与「已自定义」）、
   聊天页模型选择器（能力标签行）、远端模型选择弹窗（勾选前就能看到目录
   自动判断的结果——没配好能力也能先看出这个模型「应该」怎么配）。

## 测试
full/base 编译、单测、lint 全绿；ModelRegistryTest 保留。

# 1.6.0 追加 · 修复读图开关开了也用不了

## 现象（用户反馈）
读图能力无法使用，哪怕设置里开了也用不了；用户怀疑是工具限制与上传文件机制共同导致。

## 根因
不是端点或格式问题，是**提示词自相矛盾**：`sendCustom` 把图片（image/*）
也 stage 进沙箱，然后 `AttachmentPrompt.hint` 在用户消息里写「已放在沙箱本地，
请直接用 read_file / run_shell 处理」。同一句话里图片以 image_url（data URL）
直发了，文本却命令模型用工具去读沙箱文件——模型听了文本的，把 base64 当
文本读出乱码，于是回答「我看不到图片」。两个通道互斥，之前却同时给。

## 改法
1. `sendCustom` 附件分流提前到取配置之后：
   - 读图开：`image/*` → data URL 直发（不进沙箱、不进 hint）；
     非图片附件照旧 stage + hint。
   - 读图关：全部走沙箱路径（旧行为）。
2. `AttachmentPrompt.inlineImagesHint`：新增图片说明块，明确告诉模型
   「图片已随消息以 image_url 直发、直接看图回答、不要用 read_file /
   run_shell 读」——把「直发」这件事说破，模型不再去沙箱找文件。
3. 顺手调整：`turnCfg` 的取值提前（原来在 stage 之后才取，分流需要它在前）。

## 测试
- AttachmentPromptTest +1：inlineImagesHint 空/内容/禁止工具读的口径；
- full/base 编译、单测、lint 全绿。
