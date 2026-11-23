# CloudBuddy 架构评审与目标结构

> 评审对象：1.0.7（单模块 `:app`，8 570 行 Kotlin，33 个源文件）
> 参照标尺：Google 官方架构建议、Now in Android 模块化实践、android/ai-samples 的
> AI 应用分层、GetStream gemini-android 的聊天型 App 组织方式。
> 结论：**分层方向没错（data / ui 两包），但边界的粒度停留在"技术分层"，没有落到"职责分层"**。
> 三个文件承担了本该由十几个类分担的工作：`BuddyRepository`、`AppUiState`、`ChatScreen`。

---

## 一、外部参照：三个不同量级的规范长什么样

| 参照 | 规模 | 结构 | 对本项目的意义 |
|---|---|---|---|
| [Now in Android](https://github.com/android/nowinandroid) | 40+ 模块、多人协作 | `app` / `feature:*`（api+impl）/ `core:*`（network、data、model、ui、designsystem）/ `build-logic` | **结构本身不值得照搬**，值得学的是"依赖只能单向"这条约束 |
| [android/ai-samples](https://github.com/android/ai-samples) | 两个应用：sample-catalog + jetpacker | UI 层 / data 层，UDF，Hilt，Version Catalog | AI 能力和聊天 UI 是两件事，必须各自成层——本项目目前是混在一起的 |
| [GetStream gemini-android](https://github.com/GetStream/gemini-android) | 单应用、双 key | UI（Compose）+ ViewModel + Repository，MVVM | 与本项目体量最接近，**这是最该抄的那一份** |

Google 官方对这类规模的说法很明确：

> In small apps, you can choose to place data layer types in a `data` package or module.
> Use a domain layer with use cases **if** you need to reuse business logic across multiple ViewModels.
> — [Architecture recommendations](https://developer.android.com/topic/architecture/recommendations)

以及 Now in Android 自己写下的反模式警告：

> **Too many modules** — each module has an overhead in the form of increased complexity of the
> build configuration. **Too complex** — there is no silver bullet here.

所以：**8 500 行、单人维护的项目不该拆成 15 个 Gradle 模块**，但必须把包内的职责切干净。
拆模块是"把边界变成编译期约束"，而边界没设计好时拆模块只会把混乱固化。

---

## 二、现状诊断（都是可指到行的具体问题）

### P0-1 上帝对象：`BuddyRepository`（824 行，8 个关注点）

```text
BuddyRepository
├── 认证      sendCode / submitCode / signOut
├── 账号      refreshAccount
├── 会话列表  refreshConversations / deleteConversation / renameConversation
├── 模型      refreshModels / mergeModels / publishModels / selectModel
├── Agent 连接 connections / AcpConnection / send / stop / answerQuestion
├── 沙箱文件  loadWorkspace / readWorkspaceFile / uploadToWorkspace / deleteWorkspaceEntry
├── 计费      refreshUsage / refreshCheckin / claimCheckin
└── 输出      _ui / _workspace / _events（三套 UI 状态）
```

`data` 包里其余 11 个文件全是它的下层零件（`CloudHttp`、`WorkBuddyApi`、`SandboxFs`、
`LoginClient`…），但**只有 Repository 一个入口**，于是任何一处改动都要读这 824 行。
官方对这种写法的定义是 "Repository 变成万能工具类"，正确姿势是：

```text
Repository（协调者）
   ├── RemoteDataSource（网络）
   └── LocalDataSource（存储）
```

### P0-2 上帝状态：`AppUiState`（约 40 个字段，服务全部 6 个屏幕）

登录态、会话列表、当前消息、模型、积分、签到、成长值、工作区提示、步骤折叠表……
全部塞进一个 `data class`。后果不是"不好看"，而是：

- 任何一次 `copy()` 都让**所有** `collectAsStateWithLifecycle` 的屏幕重组（1.0.2 修的抖动就是这个根因的早期症状）；
- 聊天页和设置页的状态生命周期被强行绑定——退出会话时会把 `stepsExpanded` 一并清掉，正是因为共用一个容器。

### P0-3 没有 ViewModel：Composable 直接持有 Repository

`MainActivity.kt:96` 把 `BuddyRepository` 一路作为参数往下传，屏幕里用
`scope.launch { repo.openConversation(id) }` 直接调 suspend。

- 官方明确要求：**ViewModel 只在 screen 级使用，UI 组件不碰数据层**；
- 现状导致：旋转屏幕/进程恢复时没有状态容器可复原，所有状态只能活在 Repository 里，
  而 Repository 是 Application 单例——于是"屏幕状态"变成了"全局状态"。

### P1-1 UI 文件过长：`ChatScreen.kt` 906 行

一个文件里打包了 `ChatScreen`、`EarlierRow`、`ArtifactStrip`、`AskCard`、
`Composer`、`ModelDialog`，还有 `readAttachment(context, uri)`（Android `ContentResolver` 逻辑）。
这是"UI 层写业务"的典型：附件读取属于 data 层，不该出现在 screen 文件里。

### P1-2 构建配置不规范

- 无 `gradle/libs.versions.toml`：版本号硬编码在 `app/build.gradle.kts`，
  依赖升级要全文搜索；
- 无 convention plugin（单模块阶段不需要，但 catalog 现在就该有）；
- 无 ktlint/detekt、无 CI 工作流，规范只靠人记。

### P1-3 测试覆盖偏科

57 个测试都集中在 `data`（reducer 状态机、路径、下载命名）和 `Timeline` 纯函数投影。
这是**对的**（纯逻辑优先测），但因为缺 ViewModel，交互逻辑（发送/停止/切模型/开窗）
全部无法单测——它们现在藏在 Composable 的 lambda 里。

---

## 三、目标结构（单模块内的垂直切片）

不新增 Gradle 模块，只重排包。依赖方向严格单向：`feature → data → core`。

```text
cn.cloudbuddy.app
├── BuddyApp.kt
├── MainActivity.kt                 # 只做 setContent + 主题
│
├── app/                            # 应用级装配（唯一允许同时认识所有 feature 的地方）
│   ├── AppContainer.kt             # 手工依赖容器：一个文件替代 Hilt，30 行足够
│   ├── AppRoot.kt                  # 根 Scaffold + Tab + 预览栈
│   └── AppNav.kt                   # 路由枚举与跳转
│
├── core/                           # 与业务无关，任何层可用
│   ├── network/                    # CloudHttp、PersistentCookieJar、HttpException
│   ├── model/                      # ChatMessage、Conversation、Artifact、TurnState、ModelChoice…
│   ├── util/                       # SandboxPath、DownloadNaming、Result 扩展
│   └── designsystem/               # Theme、Loading/Empty/Error、通用 Card/Row
│
├── data/                           # 一个领域一个 Repository，各带自己的 DataSource
│   ├── auth/                       # AuthRepository、LoginClient
│   ├── session/                    # SessionRepository、SessionApi
│   ├── agent/                      # AgentRepository、AcpConnection、AcpReducer、AcpModels
│   ├── sandbox/                    # SandboxRepository、SandboxFs、FileTransfers
│   └── billing/                    # BillingRepository（积分、签到、成长值）
│
└── feature/                        # 一个屏幕一个切片：Screen + ViewModel + UiState
    ├── login/                      # LoginScreen、LoginViewModel、LoginUiState
    ├── sessions/                   # SessionsScreen、SessionsViewModel
    ├── chat/                       # ChatScreen、ChatViewModel、ChatUiState
    │   └── components/             # Composer、AskCard、ArtifactStrip、ModelSheet…
    ├── workspace/                  # WorkspaceScreen、WorkspaceViewModel
    ├── preview/                    # FilePreviewScreen、PreviewViewModel
    └── settings/                   # SettingsScreen、SettingsViewModel
```

### 每个 feature 的内部约定

```text
feature/chat/
├── ChatScreen.kt        # @Composable，无状态，只接收 uiState + onEvent
├── ChatViewModel.kt     # StateFlow<ChatUiState>，接收事件，调 Repository
├── ChatUiState.kt       # 只描述这一个屏幕
└── components/          # 只有本屏使用的 Composable
```

规则（对应官方 UDF）：

1. `Screen` 不持有 Repository，只持有 `uiState` 和 `onEvent`；
2. `ViewModel` 不引用 `Context` / `Activity` / `Resources`；
3. 事件用 `sealed interface ChatEvent`，一次性副作用（下载落盘、Toast）用 `Channel`/`SharedFlow`，
   不要塞进 `UiState`；
4. `data` 层对外暴露 `Flow` 或 `suspend`，**不暴露 `JSONObject`、`OkHttpClient`**；
5. `core` 不许 import `feature` / `data`。

### 为什么不用 Hilt

Hilt 要引入 KSP、注解处理、`@HiltAndroidApp` 和每个类的 `@Inject`，对本项目（依赖图只有
约 12 个对象、无多 variant、无动态特性）收益不抵成本。`AppContainer` 手工构造即可：

```kotlin
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    private val http = CloudHttp(settings)
    val auth = AuthRepository(settings, http)
    val sessions = SessionRepository(http, settings)
    val agent = AgentRepository(http, settings)
    val sandbox = SandboxRepository(http, settings)
    val billing = BillingRepository(http, settings)
}
```

ViewModel 通过 `viewModel { ChatViewModel(container.agent, ...) }` 拿依赖。
**等到真的出现第二个 App 变体或依赖图超过 40 个对象，再上 Hilt。**

### 为什么现在不拆 Gradle 模块

| 拆模块的收益 | 本项目是否成立 |
|---|---|
| 编译加速 | 8.5k 行，全量编译 ~40s，收益有限 |
| 并行开发、减少冲突 | 单人，不成立 |
| 强制边界 | **这条成立**，但可以用"包 + 单向依赖 + 一条 lint 规则"覆盖 |
| 按需下发 | 不成立 |

结论：**包结构先按模块化设计，代码稳定后再平移成 Gradle 模块**（包名与模块路径一一对应即可，
迁移成本极低）。这也是官方 "Too complex" 警告的实际含义。

---

## 四、迁移路线（每步都可独立编译、独立回滚）

### 第 1 步 · 纯搬运（不改一行逻辑，风险最低）

1. 建 `core/{network,model,util,designsystem}`、`data/{auth,session,agent,sandbox,billing}`、
   `feature/{login,sessions,chat,workspace,preview,settings}` 目录；
2. 按上表移动文件，只改 `package` 与 `import`；
3. `:app:assembleDebug` + 57 个单测必须全绿——**这一步不允许有任何行为变化**。

### 第 2 步 · 拆 Repository（824 行 → 5 个 100~200 行的类）

按领域切 `BuddyRepository`，用一个薄门面保持调用点不变，先拆后改：

```kotlin
class BuddyRepository(container: AppContainer) {   // 过渡期门面，逐步废弃
    val auth = container.auth
    val sessions = container.sessions
    val agent = container.agent
    ...
}
```

拆完之后，`AppUiState` 自然缩小：会话相关归 `SessionRepository`，聊天相关归 `AgentRepository`。

### 第 3 步 · 引入 ViewModel + 每屏 UiState

优先做 `chat`（最复杂、收益最大），其次 `sessions`、`settings`。
`AppUiState` 退化为 `AppShellState`（只留 `loggedIn`、`activeId`、`booting`）。

### 第 4 步 · 工程基建

- `gradle/libs.versions.toml` + `settings.gradle.kts` 里开启 `versionCatalogs`；
- ktlint（`org.jlleitschuh.gradle.ktlint`）+ detekt；
- GitHub Actions：`assembleDebug` + `testDebugUnitTest` + `lintDebug` 三件套。

### 第 5 步 · 评估是否拆模块

只有出现下列任一条件才做：多人协作、编译超过 2 分钟、需要多 App 变体。
拆的时候按 `:core:model`、`:core:network`、`:data`、`:feature:chat` 的顺序平移。

---

## 五、这次评审顺带发现的具体问题（可单独修）

| 位置 | 问题 | 建议 |
|---|---|---|
| `ui/screens/ChatScreen.kt:896` | `readAttachment(context, uri)` 在 UI 层读 `ContentResolver` | 移到 `data/attachments/AttachmentReader` |
| `data/BuddyRepository.kt:87` | `val http / login / api / sandbox` 全部 public | 改 private，只暴露门面方法 |
| `data/AppSettings.kt` | 直接吃 `SharedPreferences`，被 6 个类依赖 | 抽出 `Settings` 接口，测试可替换 |
| `MainActivity.kt:96` | Repository 作为参数贯穿 5 层 | ViewModel 化后自然消失 |
| `data/BuddyRepository.kt:99` | `acpModels` 缓存无失效策略 | 随 `SessionRepository` 归位时一并处理 |
| `AcpReducer` + `WorkBuddyApi` | 提问载荷各写一份解析；权限确认因缺 `question` 字段被整条丢弃 | **1.0.8 已修**：合并到 `data/AskParsing.kt`，一处规则两个通道共用 |

上面最后一条是这次唯一落地的重构，也是"单一职责"最便宜的一次兑现：
两个通道各自演化出的解析分支，合回一个函数后，权限确认的丢弃 bug 自己就暴露出来了。

---

## 六、判定标准

改完之后，一个新加入的人应该能在 60 秒内回答：

1. 想改聊天输入框，去哪个文件？（`feature/chat/components/Composer.kt`）
2. 想加一个接口，需要动几处？（`data/<领域>/<X>Api.kt` + 对应 Repository，不碰 UI）
3. 这个屏幕有哪些状态？（只看 `ChatUiState.kt`，一个文件）
4. 能不能在 UI 层直接发网络请求？（编译期/评审期都能拦住：UI 不依赖 `CloudHttp`）

能回答这四问，结构就算规范了；做不到，模块拆得再多也没用。
