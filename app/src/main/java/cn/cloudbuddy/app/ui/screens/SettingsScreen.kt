package cn.cloudbuddy.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.BuildConfig
import cn.cloudbuddy.app.data.Account
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.FoldMode
import cn.cloudbuddy.app.ui.screens.settings.AboutSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.AccountSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.AppearanceSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.CheckinButton
import cn.cloudbuddy.app.ui.screens.settings.CustomApiSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.Divider
import cn.cloudbuddy.app.ui.screens.settings.ExtensionsSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.checkinHint
import cn.cloudbuddy.app.ui.screens.settings.SettingCard
import cn.cloudbuddy.app.ui.screens.settings.SettingsEntryRow
import cn.cloudbuddy.app.ui.screens.settings.title
import cn.cloudbuddy.app.ui.screens.settings.balanceText
import cn.cloudbuddy.app.ui.screens.settings.customApiSummary
import kotlinx.coroutines.launch

/**
 * 设置的二级页。
 *
 * 之前所有设置挤在一页里（九个分组、一千多行），换主题要滚过模型参数、签到、
 * MCP 列表才能找到。现在按「用户想干什么」拆开：首页只留入口和最关键的一行摘要，
 * 具体项进各自的页。分组没有按代码模块分，是为了让「改外观」这事最多两跳。
 */
private sealed interface SettingsPage {
    data object Home : SettingsPage
    data object Account : SettingsPage
    data object CustomApi : SettingsPage
    data object Appearance : SettingsPage
    data object Extensions : SettingsPage
    data object About : SettingsPage
}

@Composable
fun SettingsScreen(
    ui: AppUiState,
    repo: BuddyRepository,
    onSignOut: () -> Unit
) {
    var page by remember { mutableStateOf<SettingsPage>(SettingsPage.Home) }

    // 二级页内按返回键回首页，而不是直接把整个设置 tab 退出——用户的心智是
    // 「退一层」，不是「关掉设置」。
    BackHandler(enabled = page != SettingsPage.Home) { page = SettingsPage.Home }

    when (page) {
        SettingsPage.Home -> SettingsHome(
            ui = ui,
            repo = repo,
            onSignOut = onSignOut,
            onOpen = { page = it }
        )

        SettingsPage.Account -> AccountSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.CustomApi -> CustomApiSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.Appearance -> AppearanceSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.Extensions -> ExtensionsSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.About -> AboutSettingsPage(repo) { page = SettingsPage.Home }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    ui: AppUiState,
    repo: BuddyRepository,
    onSignOut: () -> Unit,
    onOpen: (SettingsPage) -> Unit
) {
    // 首页要显示积分，进来就刷一次；签到状态也是同一批接口，顺带更新。
    LaunchedEffect(Unit) {
        repo.refreshUsage()
        repo.refreshCheckin()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            title("账号与额度", Icons.Filled.AccountCircle)

            // 账号、积分、签到合成一栏：它们本来就是同一件事——「我的账号还剩多少」。
            // 卡片只放摘要和每天要点的签到，套餐明细与账号管理在二级页；账号行本身就是
            // 那个入口（尾部箭头），不再另起一行「账号与额度」——那会和组标题同名。
            item {
                SettingCard {
                    AccountHeaderRow(
                        ui = ui,
                        repo = repo,
                        onOpen = { onOpen(SettingsPage.Account) }
                    )
                    Divider()
                    CreditRow(ui = ui, repo = repo)
                }
            }

            title("推理", Icons.Filled.Psychology)
            item {
                SettingCard {
                    // 引擎选择和端点配置是同一件事的两面：一个"用哪套"、一个"怎么连"。
                    // 拆成两个二级页时，推理页里又有"去配置端点"的入口，点进去还是这堆东西。
                    SettingsEntryRow(
                        title = "模型与推理",
                        subtitle = engineSummary(repo) + " · " + customApiSummary(repo, ui),
                        onClick = { onOpen(SettingsPage.CustomApi) }
                    )
                }
            }

            title("界面", Icons.Filled.Palette)
            item {
                SettingCard {
                    SettingsEntryRow(
                        title = "外观与对话",
                        subtitle = themeSummary(repo) + " · " + foldSummary(ui.foldMode),
                        onClick = { onOpen(SettingsPage.Appearance) }
                    )
                }
            }

            title("扩展", Icons.Filled.Extension)
            item {
                SettingCard {
                    SettingsEntryRow(
                        title = "技能与扩展",
                        subtitle = extensionsSummary(ui),
                        onClick = { onOpen(SettingsPage.Extensions) }
                    )
                }
            }

            title("其他", Icons.Filled.Info)
            item {
                SettingCard {
                    SettingsEntryRow(
                        title = "关于与更新",
                        subtitle = "版本 ${BuildConfig.VERSION_NAME} · 第三方客户端",
                        onClick = { onOpen(SettingsPage.About) }
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().clickable {
                        repo.signOut()
                        onSignOut()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("退出登录", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 账号与额度栏的头部：整行点进二级页，中间是昵称与脱敏手机号，右侧刷新。 */
@Composable
private fun AccountHeaderRow(ui: AppUiState, repo: BuddyRepository, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val name = ui.account.nickname.ifBlank {
        repo.settings.nickname.ifBlank { "WorkBuddy 账号" }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                // 脱敏规则与账号页保持一致：同一串手机号不能两个样。
                Account.maskHint(repo.settings.accountHint).ifBlank { "已登录" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 一次把三样都刷了：昵称、积分、签到状态本来就是同一批接口。
        IconButton(onClick = {
            scope.launch { repo.refreshAccount(); repo.refreshUsage(); repo.refreshCheckin() }
        }) {
            Icon(Icons.Filled.Refresh, "刷新账号与积分", Modifier.size(18.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 积分余额 + 连续签到 + 签到按钮。放在首页而不是二级页——这是天天要点的东西。 */
@Composable
private fun CreditRow(ui: AppUiState, repo: BuddyRepository) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "积分余额",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(balanceText(ui), style = MaterialTheme.typography.titleMedium)
            checkinHint(ui)?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 签到失败必须说出来：点了没反应比报错更让人困惑。
            ui.checkinError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        CheckinButton(
            loading = ui.checkinLoading,
            checkedIn = ui.checkin?.todayCheckedIn == true,
            onClick = { repo.claimCheckin() }
        )
    }
}

/** 首页那一行里的「走哪套引擎」。 */
private fun engineSummary(repo: BuddyRepository): String =
    if (repo.settings.engineMode == AppSettings.ENGINE_WB) "云端模型" else "自定义 API"

private fun themeSummary(repo: BuddyRepository): String = when (repo.settings.themeMode) {
    AppSettings.THEME_LIGHT -> "浅色主题"
    AppSettings.THEME_DARK -> "深色主题"
    else -> "跟随系统主题"
}

private fun foldSummary(mode: FoldMode): String = when (mode) {
    FoldMode.BODY -> "只折思考与工具"
    else -> "整轮折叠"
}

private fun extensionsSummary(ui: AppUiState): String {
    val parts = buildList {
        if (ui.skills.isNotEmpty()) add("${ui.skills.size} 个技能")
        if (ui.mcpServers.isNotEmpty()) add("${ui.mcpServers.size} 个 MCP 服务器")
        if (ui.connectors.isNotEmpty()) add("${ui.connectors.size} 个连接器")
    }
    return if (parts.isEmpty()) "技能、连接器与 MCP 服务器" else parts.joinToString(" · ")
}
