package cn.cloudbuddy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.ui.screens.ChatScreen
import cn.cloudbuddy.app.ui.screens.FilePreviewScreen
import cn.cloudbuddy.app.ui.screens.LoginScreen
import cn.cloudbuddy.app.ui.screens.SessionsScreen
import cn.cloudbuddy.app.ui.screens.SettingsScreen
import cn.cloudbuddy.app.ui.screens.TerminalScreen
import cn.cloudbuddy.app.ui.screens.WorkspaceScreen
import cn.cloudbuddy.app.ui.theme.CloudBuddyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // 回前台立刻对一次账：切出去这段时间云端可能已经把任务跑完了，
        // 回来还挂着「进行中」的旧角标最让人困惑。
        (application as BuddyApp).repository.setForeground(true)
    }

    override fun onPause() {
        // 后台停掉对账轮询：不跑网络、不重建连接，省电省流量。
        (application as BuddyApp).repository.setForeground(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BuddyApp
        setContent {
            val tick by app.themeTick.collectAsState()
            val settings = remember(tick) { app.settings }
            val dark = when (settings.themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            CloudBuddyTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(app.repository)
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    SESSIONS("会话", Icons.Filled.AutoAwesome),
    WORKSPACE("工作区", Icons.Filled.FolderOpen),
    SETTINGS("设置", Icons.Filled.Settings)
}

private data class PreviewTarget(val conversationId: String, val path: String, val name: String)

@Composable
private fun AppRoot(repo: BuddyRepository) {
    val ui by repo.ui.collectAsStateWithLifecycle()
    val workspace by repo.workspace.collectAsStateWithLifecycle()
    val terminal by repo.terminal.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.SESSIONS) }
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    // 命令行窗口是盖在上面的一层：它在两种引擎下都能用（沙箱是同一个），
    // 所以不做成 Tab，免得用户以为"只有自定义模式才有终端"。
    var showTerminal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repo.events.collect { message -> snackbar.showSnackbar(message) }
    }

    // 键盘让位放在最外层，只处理一次。
    //
    // enableEdgeToEdge() 把窗口设成 decorFitsSystemWindows=false 之后，清单里的
    // adjustResize 在 API 30+ 不再缩放窗口，键盘高度只以 WindowInsets.ime 的形式下发，
    // 必须由 Compose 自己消费。此前只有登录页/聊天页/终端页各自加了 imePadding()，
    // 设置、工作区、提供商等页面全都漏了——键盘一弹就把底部输入框盖住。
    // 放在这里等于恢复旧的 adjustResize 行为，一次覆盖所有页面。
    //
    // 子页面上已有的 imePadding() 不会重复留白：insets 被父级消费后，子级读到的是 0。
    Box(Modifier.fillMaxSize().imePadding()) {
        when {
            ui.booting -> SplashScreen()

            !ui.loggedIn -> {
                LoginScreen(
                    repo = repo,
                    onLoggedIn = { tab = Tab.SESSIONS },
                    // 有别的账号可回就显示返回：从「添加账号」进来的用户不该被困住。
                    onCancel = ui.accounts
                        .firstOrNull { it.id != ui.activeAccountId }
                        ?.let { { repo.removeAccount(ui.activeAccountId) } }
                )
                LaunchedEffect(ui.loggedIn) {
                    if (ui.loggedIn) tab = Tab.SESSIONS
                }
            }

            else -> {
                val target = preview
                if (target != null) {
                    FilePreviewScreen(
                        repo = repo,
                        conversationId = target.conversationId,
                        path = target.path,
                        name = target.name,
                        onBack = { preview = null }
                    )
                    BackHandler { preview = null }
                } else if (showTerminal) {
                    TerminalScreen(
                        state = terminal,
                        title = ui.activeTitle,
                        available = terminal.conversationId != null,
                        fontSize = repo.settings.terminalFontSp,
                        onFontSize = { repo.settings.terminalFontSp = it },
                        onRun = { repo.runShell(it) },
                        onStop = { repo.stopShell() },
                        onClear = { repo.clearTerminal() },
                        onNewTab = { repo.newTerminalTab() },
                        onSelectTab = { repo.selectTerminalTab(it) },
                        onCloseTab = { repo.closeTerminalTab(it) },
                        onRenameTab = { id, name -> repo.renameTerminalTab(id, name) },
                        onBack = {
                            repo.stopAllShells()
                            showTerminal = false
                        }
                    )
                    BackHandler {
                        repo.stopAllShells()
                        showTerminal = false
                    }
                } else {
                    AnimatedContent(
                        targetState = Triple(tab, ui.activeId, ui.draft),
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "root"
                    ) { state ->
                        val currentTab = state.first
                        val hasChat = state.second != null || state.third
                        if (currentTab == Tab.SESSIONS && hasChat) {
                            ChatScreen(
                                ui = ui,
                                repo = repo,
                                onBack = {
                                    repo.closeConversation()
                                    scope.launch { repo.refreshConversations() }
                                },
                                onOpenWorkspace = { tab = Tab.WORKSPACE },
                                onOpenTerminal = {
                                    repo.openTerminal(ui.activeId)
                                    showTerminal = true
                                },
                                onOpenSettings = { tab = Tab.SETTINGS }
                            )
                        } else {
                            Scaffold(
                                bottomBar = {
                                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                        Tab.entries.forEach { item ->
                                            NavigationBarItem(
                                                selected = tab == item,
                                                onClick = { tab = item },
                                                icon = { Icon(item.icon, item.label) },
                                                label = { Text(item.label) }
                                            )
                                        }
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.background
                            ) { padding ->
                                // consumeWindowInsets 是这里的关键：这一层 Scaffold 的 padding
                                // 已经把内容整体让到状态栏下方（顶部）和导航栏上方（底部），
                                // 但 Modifier.padding 只挪位置、**不消费** inset。里面每个页面
                                // 自带的 TopAppBar 会再读一次 WindowInsets.systemBars，于是状态栏
                                // 高度被算两遍，页面顶部凭空多出一截空白（导航栏同理，底部多一截）。
                                // 把用掉的这份 padding 标记成已消费，内层读到的对应 inset 归零。
                                // 同一个原因也解释了为什么聊天页没有这个空白：它渲染在这一层之外。
                                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                    when (currentTab) {
                                        Tab.SESSIONS -> SessionsScreen(
                                            conversations = ui.conversations,
                                            nickname = ui.account.nickname.ifBlank { repo.settings.nickname },
                                            onOpen = { id -> scope.launch { repo.openConversation(id) } },
                                            onNew = {
                                                preview = null
                                                tab = Tab.SESSIONS
                                                repo.startNew()
                                            },
                                            onRefresh = { repo.refreshConversations() },
                                            onDelete = { repo.deleteConversation(it) },
                                            onRename = { id, title -> repo.renameConversation(id, title) },
                                            onResetRun = { repo.forceResetConversation(it) },
                                            balanceLabel = ui.growth?.let { "${it.balance} 积分" }.orEmpty(),
                                            offline = ui.offline,
                                            // 归属存在本机偏好里（云端没有这个概念），按会话 id 逐个判。
                                            // 键在 conversations 上：列表一刷新就重算，不额外收一次状态。
                                            localIds = remember(ui.conversations) {
                                                ui.conversations
                                                    .filter { repo.settings.isLocal(it.id) }
                                                    .map { it.id }
                                                    .toSet()
                                            }
                                        )

                                        Tab.WORKSPACE -> WorkspaceScreen(
                                            ui = ui,
                                            workspace = workspace,
                                            repo = repo,
                                            onPreview = { conversation, path, name ->
                                                preview = PreviewTarget(conversation, path, name)
                                            },
                                            onOpenSession = {
                                                tab = Tab.SESSIONS
                                                scope.launch { snackbar.showSnackbar("先进入一个会话，再回到工作区") }
                                            },
                                            onOpenTerminal = {
                                                repo.openTerminal(workspace.conversationId)
                                                showTerminal = true
                                            }
                                        )

                                        Tab.SETTINGS -> SettingsScreen(
                                            ui = ui,
                                            repo = repo,
                                            onSignOut = { tab = Tab.SESSIONS }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    BackHandler(enabled = tab != Tab.SESSIONS || ui.activeId != null || ui.draft) {
                        when {
                            ui.activeId != null -> {
                                repo.closeConversation()
                                scope.launch { repo.refreshConversations() }
                            }

                            else -> tab = Tab.SESSIONS
                        }
                    }
                }
            }
        }
        // 统一提示层：此前 ChatScreen 分支没有 SnackbarHost，模型切换结果等事件
        // 在聊天页完全看不到，用户会以为「选了没生效」。
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
        )
    }
}

@Composable
private fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("云伴", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "正在连接云端环境…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}
