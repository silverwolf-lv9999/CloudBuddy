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
import androidx.compose.foundation.layout.fillMaxSize
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
import cn.cloudbuddy.app.ui.screens.WorkspaceScreen
import cn.cloudbuddy.app.ui.theme.CloudBuddyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.SESSIONS) }
    var preview by remember { mutableStateOf<PreviewTarget?>(null) }

    LaunchedEffect(Unit) {
        repo.events.collect { message -> snackbar.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.booting -> SplashScreen()

            !ui.loggedIn -> {
                LoginScreen(repo) { tab = Tab.SESSIONS }
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
                                onOpenWorkspace = { tab = Tab.WORKSPACE }
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
                                Box(Modifier.fillMaxSize().padding(padding)) {
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
                                            balanceLabel = ui.growth?.let { "${it.balance} 积分" }.orEmpty(),
                                            offline = ui.offline
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
