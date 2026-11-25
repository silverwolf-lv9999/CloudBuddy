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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
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
import cn.cloudbuddy.app.data.Edition
import cn.cloudbuddy.app.data.FoldMode
import cn.cloudbuddy.app.ui.screens.settings.AboutSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.AccountSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.AppearanceSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.CheckinButton
import cn.cloudbuddy.app.ui.screens.settings.CustomApiSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.Divider
import cn.cloudbuddy.app.ui.screens.settings.CloudSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.LocalSkillsPage
import cn.cloudbuddy.app.ui.screens.settings.PromptSettingsPage
import cn.cloudbuddy.app.ui.screens.settings.checkinHint
import cn.cloudbuddy.app.ui.screens.settings.SettingCard
import cn.cloudbuddy.app.ui.screens.settings.SettingsEntryRow
import cn.cloudbuddy.app.ui.screens.settings.title
import cn.cloudbuddy.app.ui.screens.settings.balanceText
import cn.cloudbuddy.app.ui.screens.settings.customApiSummary
import cn.cloudbuddy.app.ui.screens.settings.promptSummary
import kotlinx.coroutines.launch

/**
 * 设置的二级页。
 *
 * 之前所有设置挤在一页里（九个分组、一千多行），换主题要滚过模型参数、签到、
 * MCP 列表才能找到。现在按「用户想干什么」拆开：首页只留入口和最关键的一行摘要，
 * 具体项进各自的页。分组没有按代码模块分，是为了让「改外观」这事最多两跳。
 *
 * 分组的主轴是**能力归属**，不是参数类型：这个 App 有两套同时存在的后端——
 * 云端账号（积分、技能、连接器、MCP）和本机自定义 API（端点、模型、提示词）。
 * 以前它们被混在「推理」「扩展」这种按功能起的名字下（技能在「扩展」、提示词在
 * 「推理」），用户没法判断某个开关到底影响哪套会话。现在一条界线划到底：
 * 只给云端用的东西放「云端会话」，只给本机用的放「本机模型」，两边都生效的
 * （主题、折叠、回车发送）才进「通用」。
 */
private sealed interface SettingsPage {
    data object Home : SettingsPage
    data object Account : SettingsPage
    data object Local : SettingsPage
    data object Prompts : SettingsPage
    data object LocalSkills : SettingsPage
    data object Appearance : SettingsPage
    data object Cloud : SettingsPage
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
        SettingsPage.Local -> CustomApiSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.Prompts -> PromptSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.LocalSkills -> LocalSkillsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.Appearance -> AppearanceSettingsPage(ui, repo) { page = SettingsPage.Home }
        SettingsPage.Cloud -> CloudSettingsPage(ui, repo) { page = SettingsPage.Home }
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

            // 精简版没有引擎可选，这一组整块不出现；留一个空入口只会让人点进去看
            // 一页「本版本不支持」。
            if (Edition.full) {
                // 引擎切换是唯一一个「跨两套后端」的开关，所以它不归任何一边，单独一栏。
                // 放在最前是因为它决定后面两栏哪一栏与你有关：选了云端，本机模型那栏
                // 就是给「以后要换」准备的；反过来同理。
                title("会话引擎", Icons.Filled.SwapHoriz, hint = "新建会话默认用哪套后端")
                item { EngineCard(repo) }
            }

            title("云端会话", Icons.Filled.Cloud, hint = "扣账号积分，任务在云端 agent 里执行")
            item {
                SettingCard {
                    // 行名用具体的三样东西，不用「云端能力」这种抽象词：用户找的是
                    // 「我的技能去哪了」，不是「能力管理」。和栏目标题也不重名。
                    SettingsEntryRow(
                        title = "技能、连接器与 MCP",
                        subtitle = cloudSummary(ui),
                        onClick = { onOpen(SettingsPage.Cloud) }
                    )
                }
            }

            if (Edition.full) {
                title("本机模型", Icons.Filled.Psychology, hint = "多提供商 + 模型管理，走你自己的 API，不扣积分")
                item {
                    SettingCard {
                        SettingsEntryRow(
                            title = "接口与模型",
                            subtitle = customApiSummary(repo, ui),
                            onClick = { onOpen(SettingsPage.Local) }
                        )
                        Divider()
                        // 提示词片段只作用于本机会话（拼进它的系统提示词），所以归在这一栏；
                        // 单独一页是因为它是「一批可复用的规矩」，和端点/温度不是同一类参数。
                        SettingsEntryRow(
                            title = "提示词片段",
                            subtitle = promptSummary(ui),
                            onClick = { onOpen(SettingsPage.Prompts) }
                        )
                        Divider()
                        // 技能与 MCP 各有一份「本机版」：云端的在「云端会话」栏，
                        // 这里的是喂给本机 agent 的，两套互不影响。
                        SettingsEntryRow(
                            title = "本机技能",
                            subtitle = localSkillSummary(ui),
                            onClick = { onOpen(SettingsPage.LocalSkills) }
                        )
                    }
                }
            }

            title("通用", Icons.Filled.Tune, hint = "两套会话都生效")
            item {
                SettingCard {
                    SettingsEntryRow(
                        title = "外观与对话",
                        subtitle = themeSummary(repo) + " · " + foldSummary(ui.foldMode),
                        onClick = { onOpen(SettingsPage.Appearance) }
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

/**
 * 「新会话用哪套后端」这一个开关。
 *
 * 它就是整个设置页的分水岭：上面一栏（云端）和下面一栏（本机）各自只对选中的那套
 * 有意义。所以这里直接给两个 chip，点一下就切换，不再让用户点进二级页去找。
 */
@Composable
private fun EngineCard(repo: BuddyRepository) {
    var engineMode by remember { mutableStateOf(repo.settings.engineMode) }
    SettingCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AppSettings.ENGINE_WB to "云端模型",
                    AppSettings.ENGINE_CUSTOM to "自定义 API"
                ).forEach { (value, label) ->
                    val active = engineMode == value
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            engineMode = value
                            repo.settings.engineMode = value
                        }
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                if (engineMode == AppSettings.ENGINE_WB) {
                    "走云端会话协议：扣账号积分，任务在云端 agent 里执行，「云端会话」那一栏的技能、" +
                        "连接器、MCP 都在这儿生效。"
                } else {
                    // 自定义引擎的定位说清楚：省的是积分，不是沙箱。
                    "走本机 agent + 你自己的 OpenAI 兼容接口：不扣积分、不产生云端对话记录，" +
                        "工具调用仍在云端沙箱（/workspace）里执行。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "两套会话彻底分开：云端会话只能用云端模型，本机会话只能用你接口上的模型，" +
                    "会话列表里有「云端 / 本机」标签。类型在新建时定下，之后不再互转——" +
                    "一条会话两种引擎跑出来的历史本来就对不上号。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

private fun themeSummary(repo: BuddyRepository): String = when (repo.settings.themeMode) {
    AppSettings.THEME_LIGHT -> "浅色主题"
    AppSettings.THEME_DARK -> "深色主题"
    else -> "跟随系统主题"
}

private fun foldSummary(mode: FoldMode): String = when (mode) {
    FoldMode.BODY -> "只折思考与工具"
    else -> "整轮折叠"
}

/** 本机技能的摘要。空着时给一句提示，免得入口看起来像个没用的开关。 */
private fun localSkillSummary(ui: AppUiState): String {
    val all = ui.localSkills
    if (all.isEmpty()) return "放 SKILL.md，只对本机会话生效"
    val enabled = all.count { it.enabled }
    return "已启用 $enabled 条 · 共 ${all.size} 条"
}

/** 云端那一栏的摘要：技能 / 连接器 / MCP 的数量。 */
private fun cloudSummary(ui: AppUiState): String {
    val parts = buildList {
        if (ui.skills.isNotEmpty()) add("${ui.skills.size} 个技能")
        if (ui.mcpServers.isNotEmpty()) add("${ui.mcpServers.size} 个 MCP 服务器")
        if (ui.connectors.isNotEmpty()) add("${ui.connectors.size} 个连接器")
    }
    return if (parts.isEmpty()) "技能、连接器与 MCP 服务器" else parts.joinToString(" · ")
}
