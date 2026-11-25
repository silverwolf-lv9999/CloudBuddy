package cn.cloudbuddy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
        (application as BuddyApp).repository.setForeground(true)
    }

    override fun onPause() {
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
                            // 结构照搬 KernelSU（manager/.../ui/MainActivity.kt）：
                            // 悬浮栏放进 Scaffold 的 bottomBar，内容通过 innerPadding 自动避让，
                            // 不再用 Box 叠一层——叠层会挡住内容底部的按钮（新建会话 FAB 就是被它压住的）。
                            // 另外补一条 KernelSU 没有的：列表下滑时收起悬浮栏，上滑或触顶时再浮出。
                            var barVisible by remember { mutableStateOf(true) }
                            val connection = remember {
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
                                containerColor = MaterialTheme.colorScheme.background,
                                bottomBar = {
                                    Box(Modifier.fillMaxWidth()) {
                                        AnimatedVisibility(
                                            visible = barVisible,
                                            enter = slideInVertically { it } + fadeIn(),
                                            exit = slideOutVertically { it } + fadeOut(),
                                            modifier = Modifier.align(Alignment.BottomCenter)
                                        ) {
                                            FloatingTabBar(
                                                currentTab = currentTab,
                                                onTabSelected = { tab = it }
                                            )
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                // 胶囊可见时 bottomBar 高度已含底部留白；收起后 bottomBar
                                // 高度归零，若只用 innerPadding，内容和 FAB 会落到系统导航栏
                                // 下面。取两者的较大值，收起状态仍保留 navigationBars 的避让。
                                val navInset = WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                                val contentBottom = maxOf(
                                    innerPadding.calculateBottomPadding(),
                                    navInset
                                )
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .nestedScroll(connection)
                                        .padding(bottom = contentBottom)
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

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
        )
    }
}

/**
 * 悬浮胶囊导航栏。结构照搬 KernelSU
 * （manager/.../ui/component/bottombar/BottomBarMiuix.kt 的 FloatingBottomBar 分支），
 * 底部留白按本应用需要收紧：
 * - 胶囊总高 64dp，内部 padding 4dp（内容 56dp）
 * - 左右各留 28dp，底部按导航栏 inset 决定（有 inset 时 8dp + inset，否则 12dp）
 * - 宽度按内容 intrinsic 收缩，不铺满整行
 */
@Composable
private fun FloatingTabBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    // 有手势/三键导航栏 inset 时贴紧凑；没有 inset（沉浸式/无系统栏）时也只留
    // 12dp 呼吸空间，不能沿用 28dp 那种大间距，否则胶囊下方会多出一截空白。
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = if (navInset != 0.dp) 8.dp + navInset else 12.dp

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .height(64.dp)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tab.entries.forEach { item ->
                    val selected = currentTab == item
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .defaultMinSize(minWidth = 76.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { onTabSelected(item) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
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
