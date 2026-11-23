package cn.cloudbuddy.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.BuildConfig
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: AppUiState,
    repo: BuddyRepository,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(repo.settings.themeMode) }
    var dynamic by remember { mutableStateOf(repo.settings.dynamicColor) }
    var showThoughts by remember { mutableStateOf(repo.settings.showThoughts) }
    var historyTurns by remember { mutableStateOf(repo.settings.historyTurns) }
    var historyTimeout by remember { mutableStateOf(repo.settings.historyTimeoutSec) }

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
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                ui.account.nickname.ifBlank { repo.settings.nickname.ifBlank { "WorkBuddy 账号" } },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                repo.settings.accountHint.ifBlank { "已登录" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.clickable {
                                scope.launch { repo.refreshAccount(); repo.refreshUsage() }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("刷新", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            item { SectionTitle("积分与签到", Icons.Filled.Star) }
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("积分余额", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    balanceText(ui),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            CheckinButton(
                                loading = ui.checkinLoading,
                                checkedIn = ui.checkin?.todayCheckedIn == true,
                                onClick = { repo.claimCheckin() }
                            )
                        }
                        checkinHint(ui)?.let { hint ->
                            Text(
                                hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ui.checkinError?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (ui.usage.isNotEmpty()) {
                            Divider()
                            ui.usage.take(3).forEach { pack ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        pack.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${pack.left.toInt()} / ${pack.total.toInt()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("云端环境", Icons.Filled.Cloud) }
            item {
                SettingCard {
                    SettingRow(
                        title = "服务站点",
                        subtitle = repo.settings.baseUrl,
                        trailing = { Text("官方", style = MaterialTheme.typography.labelSmall) }
                    )
                    Divider()
                    SettingRow(
                        title = "当前模型",
                        subtitle = ui.selectedModel.ifBlank { AppSettings.DEFAULT_MODEL },
                        trailing = { Text("${ui.models.size} 个可选", style = MaterialTheme.typography.labelSmall) }
                    )
                    Divider()
                    SettingRow(
                        title = "会话执行位置",
                        subtitle = "官方云端沙箱（/workspace），不在本机执行",
                        trailing = {
                            Text(
                                "云沙箱",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }

            item { SectionTitle("外观", Icons.Filled.Palette) }
            item {
                SettingCard {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("主题", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                AppSettings.THEME_SYSTEM to "跟随系统",
                                AppSettings.THEME_LIGHT to "浅色",
                                AppSettings.THEME_DARK to "深色"
                            ).forEach { (value, label) ->
                                val active = themeMode == value
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable {
                                        themeMode = value
                                        repo.settings.themeMode = value
                                        (context.applicationContext as? cn.cloudbuddy.app.BuddyApp)?.applyTheme()
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
                    }
                    Divider()
                    SettingRow(
                        title = "动态取色",
                        subtitle = "跟随系统壁纸生成配色（Android 12+）",
                        trailing = {
                            Switch(checked = dynamic, onCheckedChange = {
                                dynamic = it
                                repo.settings.dynamicColor = it
                                (context.applicationContext as? cn.cloudbuddy.app.BuddyApp)?.applyTheme()
                            })
                        }
                    )
                }
            }

            item { SectionTitle("对话", Icons.Filled.Tune) }
            item {
                SettingCard {
                    SettingRow(
                        title = "折叠中间步骤",
                        subtitle = "只保留提问与最终回答；之后新产生的步骤也自动折叠",
                        trailing = {
                            Switch(
                                checked = ui.stepsCollapsed,
                                onCheckedChange = { repo.setCollapseSteps(it) }
                            )
                        }
                    )
                    Divider()
                    SettingRow(
                        title = "展开思考过程",
                        subtitle = "默认把模型的思考分片折叠起来",
                        trailing = {
                            Switch(checked = showThoughts, onCheckedChange = {
                                showThoughts = it
                                repo.settings.showThoughts = it
                            })
                        }
                    )
                    Divider()
                    ChipSetting(
                        title = "一次渲染多少轮对话",
                        subtitle = "只渲染最近若干轮，更早的往上翻再加载；长对话打开更快",
                        options = listOf(10 to "10 轮", 20 to "20 轮", 50 to "50 轮", 0 to "全部"),
                        selected = historyTurns
                    ) {
                        historyTurns = it
                        repo.settings.historyTurns = it
                    }
                    Divider()
                    ChipSetting(
                        title = "历史加载超时",
                        subtitle = "对话越长云端回放越慢；超时只会少显示早期内容，不影响继续对话",
                        options = listOf(60 to "60 秒", 120 to "120 秒", 180 to "180 秒", 300 to "300 秒"),
                        selected = historyTimeout
                    ) {
                        historyTimeout = it
                        repo.settings.historyTimeoutSec = it
                    }
                }
            }

            item { SectionTitle("关于", Icons.Filled.Info) }
            item {
                SettingCard {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("云伴 WorkBuddy", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "版本 ${BuildConfig.VERSION_NAME} · 第三方客户端",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "本客户端直接对接 WorkBuddy 官方云端会话协议（console 接口 + ACP 事件流 + 沙箱文件网关），" +
                                "任务在官方云端沙箱内执行，产物保存在沙箱 /workspace 中，" +
                                "不会把模型能力反向代理成本地 API。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        Icon(Icons.Filled.ExitToApp, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("退出登录", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** 一行标题 + 副标题 + 一排可选项（用于「渲染多少轮」「超时多少秒」）。 */
@Composable
private fun ChipSetting(
    title: String,
    subtitle: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val active = selected == value
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onSelect(value) }
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Divider() {    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun CheckinButton(loading: Boolean, checkedIn: Boolean, onClick: () -> Unit) {
    val enabled = !loading && !checkedIn
    Surface(
        shape = RoundedCornerShape(50),
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = when {
                loading -> "处理中…"
                checkedIn -> "今日已签到"
                else -> "签到"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** 余额文案：有等级时一起展示，避免再多一行。 */
private fun balanceText(ui: AppUiState): String {
    val growth = ui.growth ?: return "正在获取…"
    val level = if (growth.level > 0) {
        " · Lv.${growth.level}" + if (growth.levelName.isNotBlank()) " ${growth.levelName}" else ""
    } else {
        ""
    }
    return "${growth.balance} 积分$level"
}

/** 签到副标题：连续天数 / 今日所得 / 可得积分。 */
private fun checkinHint(ui: AppUiState): String? {
    val info = ui.checkin ?: return null
    val parts = buildList {
        if (info.streakDays > 0) add("连续签到 ${info.streakDays} 天")
        if (info.todayCheckedIn) {
            if (info.todayCredit > 0) add("今日 +${info.todayCredit}")
        } else if (info.dailyCredit > 0) {
            add("签到可得 ${info.dailyCredit}")
        }
        if (!info.active) add("签到活动未开启")
    }
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}
