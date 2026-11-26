package cn.cloudbuddy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import cn.cloudbuddy.app.ui.components.AppNavBar
import cn.cloudbuddy.app.ui.components.NavBarItem
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            // 导航栏形态这类本机外观设置不走主题流，单独一个计数触发重读。
            val uiTick by app.uiTick.collectAsState()
            val settings = remember(tick, uiTick) { app.settings }
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
    // 导航项只建一次：每次重组都新建列表会让导航栏的边界测量失效、指示器闪一下。
    val navItems = remember { Tab.entries.map { NavBarItem(it.label, it.icon) } }
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }
    // 命令行窗口是盖在上面的一层：它在两种引擎下都能用（沙箱是同一个），
    // 所以不做成 Tab，免得用户以为"只有自定义模式才有终端"。
    var showTerminal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repo.events.collect { message -> snackbar.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize()) {
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
                            // 悬浮形态：列表下滑时收起、上滑或触顶浮出（照 PR #1 的
                            // NestedScrollConnection 做法）。通栏形态始终可见，不参与收起。
                            val floatingBar = repo.settings.navBarStyle != AppSettings.NAV_DOCKED
                            var barVisible by remember { mutableStateOf(true) }
                            val hideOnScroll = remember {
                                object : NestedScrollConnection {
                                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                        if (source == NestedScrollSource.UserInput) {
                                            if (available.y < -2f) barVisible = false
                                            else if (available.y > 2f) barVisible = true
                                        }
                                        return Offset.Zero
                                    }
                                }
                            }
                            Scaffold(
                                bottomBar = {
                                    if (floatingBar) {
                                        Box(Modifier.fillMaxWidth()) {
                                            AnimatedVisibility(
                                                visible = barVisible,
                                                enter = slideInVertically { it } + fadeIn(),
                                                exit = slideOutVertically { it } + fadeOut(),
                                                modifier = Modifier.align(Alignment.BottomCenter)
                                            ) {
                                                AppNavBar(
                                                    items = navItems,
                                                    selectedIndex = Tab.entries.indexOf(currentTab),
                                                    onSelect = { tab = Tab.entries[it] },
                                                    floating = true
                                                )
                                            }
                                        }
                                    } else {
                                        AppNavBar(
                                            items = navItems,
                                            selectedIndex = Tab.entries.indexOf(currentTab),
                                            onSelect = { tab = Tab.entries[it] },
                                            floating = false
                                        )
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.background
                            ) { padding ->
                                // 胶囊收起后 bottomBar 高度归零，只按 innerPadding 排会把内容
                                // 顶到系统导航栏下面；取两者较大值，收起态仍保留 navigationBars 避让。
                                val navInset = WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                                val contentBottom = maxOf(padding.calculateBottomPadding(), navInset)
                                val layoutDir = LocalLayoutDirection.current
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .nestedScroll(hideOnScroll)
                                        .padding(
                                            PaddingValues(
                                                start = padding.calculateStartPadding(layoutDir),
                                                top = padding.calculateTopPadding(),
                                                end = padding.calculateEndPadding(layoutDir),
                                                bottom = contentBottom
                                            )
                                        )
                                ) {
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
