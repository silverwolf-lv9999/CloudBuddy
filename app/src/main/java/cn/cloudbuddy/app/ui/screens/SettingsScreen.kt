package cn.cloudbuddy.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.BuildConfig
import cn.cloudbuddy.app.data.Account
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.FoldMode
import cn.cloudbuddy.app.data.ReleaseInfo
import cn.cloudbuddy.app.data.UpdateChecker
import cn.cloudbuddy.app.ui.components.ConnectorCard
import cn.cloudbuddy.app.ui.components.McpCard
import cn.cloudbuddy.app.ui.components.SkillsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    var enterSend by remember { mutableStateOf(repo.settings.enterSend) }
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    var historyTurns by remember { mutableStateOf(repo.settings.historyTurns) }
    var historyTimeout by remember { mutableStateOf(repo.settings.historyTimeoutSec) }
    var updateState by remember { mutableStateOf<UpdateCheck>(UpdateCheck.Idle) }

    /**
     * 检查更新。四种结果必须分开呈现：把「网络失败」显示成「已是最新」，
     * 等于骗用户说当前版本不用升。
     */
    fun checkUpdate() {
        if (updateState is UpdateCheck.Checking) return
        updateState = UpdateCheck.Checking
        scope.launch {
            updateState = try {
                val info = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                when {
                    info == null -> UpdateCheck.UpToDate
                    UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME) ->
                        UpdateCheck.Available(info)
                    else -> UpdateCheck.UpToDate
                }
            } catch (e: Exception) {
                UpdateCheck.Failed(e.message ?: "网络不可用")
            }
        }
    }

    LaunchedEffect(Unit) {
        repo.refreshUsage()
        repo.refreshCheckin()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLargeEmphasized
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 顶部账号卡 ────────────────────────────────────────────────
            item {
                Surface(
                    shape = MaterialTheme.shapes.extraLargeIncreased,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Person,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                ui.account.nickname.ifBlank { repo.settings.nickname.ifBlank { "WorkBuddy 账号" } },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                // 顶部账号卡和下面账号列表必须是同一套脱敏规则：
                                // 之前这里直接贴完整手机号，下面却显示 189****4407，同一串数字两种样。
                                Account.maskHint(repo.settings.accountHint).ifBlank { "已登录" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.largeIncreased,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.clickable {
                                scope.launch { repo.refreshAccount(); repo.refreshUsage() }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("刷新", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // ── 账号 ──────────────────────────────────────────────────────
            item {
                SettingsGroup(title = "账号") {
                    ui.accounts.forEachIndexed { index, account ->
                        GroupRow(
                            onClick = if (account.id != ui.activeAccountId) {
                                { repo.switchAccount(account.id) }
                            } else null
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        val initial = account.title.take(1)
                                        if (initial.isNotBlank()) {
                                            Text(
                                                initial,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        } else {
                                            Icon(
                                                Icons.Filled.Person,
                                                null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(account.title, style = MaterialTheme.typography.titleSmall)
                                    if (account.subtitle.isNotBlank()) {
                                        Text(
                                            account.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (account.id == ui.activeAccountId) {
                                    Surface(
                                        shape = MaterialTheme.shapes.largeIncreased,
                                        color = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text(
                                            "当前",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                } else {
                                    IconButton(onClick = { repo.switchAccount(account.id) }) {
                                        Icon(
                                            Icons.Filled.SwapHoriz,
                                            "切换到该账号",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(onClick = { pendingDelete = account }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        "删除账号",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (index != ui.accounts.lastIndex) GroupDivider()
                    }
                    if (ui.accounts.isNotEmpty()) GroupDivider()
                    GroupRow(onClick = { repo.addAccount() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Add,
                                        "添加账号",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("添加账号", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "登录另一个账号；两个账号的登录态各自保留，切换不用重新收验证码",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── 积分与签到 ────────────────────────────────────────────────
            item {
                SettingsGroup(title = "积分与签到") {
                    GroupRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("积分余额", style = MaterialTheme.typography.titleSmall)
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
                    }
                    val hints = buildList {
                        checkinHint(ui)?.let { add(it to false) }
                        ui.checkinError?.let { add(it to true) }
                    }
                    hints.forEach { (hint, isError) ->
                        GroupDivider()
                        GroupRow {
                            Text(
                                hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (ui.usage.isNotEmpty()) {
                        GroupDivider()
                        ui.usage.take(3).forEachIndexed { index, pack ->
                            GroupRow {
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
                            if (index != minOf(2, ui.usage.lastIndex)) GroupDivider()
                        }
                    }
                }
            }

            // ── 云端环境 ──────────────────────────────────────────────────
            item {
                SettingsGroup(title = "云端环境") {
                    GroupRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupTextColumn(
                                title = "服务站点",
                                subtitle = repo.settings.baseUrl,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("官方", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    GroupDivider()
                    GroupRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupTextColumn(
                                title = "当前模型",
                                subtitle = ui.selectedModel.ifBlank { AppSettings.DEFAULT_MODEL },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("${ui.models.size} 个可选", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    GroupDivider()
                    GroupRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupTextColumn(
                                title = "会话执行位置",
                                subtitle = "官方云端沙箱（/workspace），不在本机执行",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "云沙箱",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── 外观 ──────────────────────────────────────────────────────
            item {
                SettingsGroup(title = "外观") {
                    GroupRow {
                        Column {
                            Text("主题", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "浅色 / 深色 / 跟随系统",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    AppSettings.THEME_SYSTEM to "跟随系统",
                                    AppSettings.THEME_LIGHT to "浅色",
                                    AppSettings.THEME_DARK to "深色"
                                ).forEach { (value, label) ->
                                    val active = themeMode == value
                                    Surface(
                                        shape = MaterialTheme.shapes.largeIncreased,
                                        color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier.clickable {
                                            themeMode = value
                                            repo.settings.themeMode = value
                                            (context.applicationContext as? cn.cloudbuddy.app.BuddyApp)?.applyTheme()
                                        }
                                    ) {
                                        Text(
                                            label,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (active) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    GroupDivider()
                    SwitchRow(
                        title = "动态取色",
                        subtitle = "跟随系统壁纸生成配色（Android 12+）",
                        checked = dynamic,
                        onCheckedChange = {
                            dynamic = it
                            repo.settings.dynamicColor = it
                            (context.applicationContext as? cn.cloudbuddy.app.BuddyApp)?.applyTheme()
                        }
                    )
                }
            }

            // ── 对话 ──────────────────────────────────────────────────────
            item {
                SettingsGroup(title = "对话") {
                    ChipRow(
                        title = "折叠方式",
                        subtitle = "整轮折叠：除最终回答外全收起；只折过程：正文保留，思考与工具调用收成一条",
                        options = listOf(
                            FoldMode.WHOLE.key to "整轮折叠",
                            FoldMode.BODY.key to "只折思考与工具"
                        ),
                        selected = ui.foldMode.key,
                        onSelect = { repo.setFoldMode(FoldMode.of(it)) }
                    )
                    GroupDivider()
                    SwitchRow(
                        title = "折叠中间步骤",
                        subtitle = "只保留提问与最终回答；之后新产生的步骤也自动折叠",
                        checked = ui.stepsCollapsed,
                        onCheckedChange = { repo.setCollapseSteps(it) }
                    )
                    GroupDivider()
                    SwitchRow(
                        title = "展开思考过程",
                        subtitle = "默认把模型的思考分片折叠起来",
                        checked = showThoughts,
                        onCheckedChange = {
                            showThoughts = it
                            repo.settings.showThoughts = it
                        }
                    )
                    GroupDivider()
                    SwitchRow(
                        title = "回车发送消息",
                        subtitle = if (enterSend) "回车直接发送" else "回车换行；发送请点右下角按钮",
                        checked = enterSend,
                        onCheckedChange = {
                            enterSend = it
                            repo.settings.enterSend = it
                        }
                    )
                    GroupDivider()
                    ChipRow(
                        title = "一次渲染多少轮对话",
                        subtitle = "只渲染最近若干轮，更早的往上翻再加载；长对话打开更快",
                        options = listOf(10 to "10 轮", 20 to "20 轮", 50 to "50 轮", 0 to "全部"),
                        selected = historyTurns,
                        onSelect = {
                            historyTurns = it
                            repo.settings.historyTurns = it
                        }
                    )
                    GroupDivider()
                    ChipRow(
                        title = "历史加载超时",
                        subtitle = "对话越长云端回放越慢；超时只会少显示早期内容，不影响继续对话",
                        options = listOf(60 to "60 秒", 120 to "120 秒", 180 to "180 秒", 300 to "300 秒"),
                        selected = historyTimeout,
                        onSelect = {
                            historyTimeout = it
                            repo.settings.historyTimeoutSec = it
                        }
                    )
                }
            }

            // ── 技能 ──────────────────────────────────────────────────────
            item { GroupLabel("技能") }
            item { SkillsCard(ui, repo) }

            // ── MCP ───────────────────────────────────────────────────────
            item { GroupLabel("MCP 服务器") }
            item { ConnectorCard(ui, repo) }
            item { McpCard(ui, repo) }

            // ── 关于 ──────────────────────────────────────────────────────
            item {
                SettingsGroup(title = "关于") {
                    GroupRow {
                        Column {
                            Text("云伴 WorkBuddy", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "版本 ${BuildConfig.VERSION_NAME} · 第三方客户端",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "本客户端直接对接 WorkBuddy 官方云端会话协议（console 接口 + ACP 事件流 + 沙箱文件网关），" +
                                    "任务在官方云端沙箱内执行，产物保存在沙箱 /workspace 中，" +
                                    "不会把模型能力反向代理成本地 API。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    GroupDivider()
                    GroupRow(onClick = {
                        val current = updateState
                        if (current is UpdateCheck.Available) {
                            openUrl(context, current.info.apkUrl ?: current.info.pageUrl)
                        } else {
                            checkUpdate()
                        }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupTextColumn(
                                title = "检查更新",
                                subtitle = when (val s = updateState) {
                                    UpdateCheck.Idle -> "从 GitHub Releases 获取最新版本"
                                    UpdateCheck.Checking -> "正在连接 GitHub…"
                                    UpdateCheck.UpToDate -> "当前 ${BuildConfig.VERSION_NAME} 已是最新"
                                    is UpdateCheck.Available -> "发现新版本 ${s.info.version}，点击查看"
                                    is UpdateCheck.Failed -> "检查失败：${s.message}"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            val accent = updateState is UpdateCheck.Available
                            Text(
                                when (val s = updateState) {
                                    UpdateCheck.Idle -> "检查"
                                    UpdateCheck.Checking -> "…"
                                    UpdateCheck.UpToDate -> "已是最新"
                                    is UpdateCheck.Available -> "v${s.info.version}"
                                    is UpdateCheck.Failed -> "重试"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (accent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    GroupDivider()
                    GroupRow(onClick = { openUrl(context, UpdateChecker.REPO_URL) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupTextColumn(
                                title = "GitHub 仓库",
                                subtitle = UpdateChecker.REPO_SLUG,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "打开",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    GroupDivider()
                    GroupRow(onClick = {
                        openUrl(context, "https://github.com/${UpdateChecker.REPO_SLUG}/issues")
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupTextColumn(
                                title = "提交反馈",
                                subtitle = "遇到问题或有想法？在 GitHub Issues 里告诉我们",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "打开",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── 退出登录 ──────────────────────────────────────────────────
            item {
                Surface(
                    shape = MaterialTheme.shapes.extraLargeIncreased,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().clickable {
                        repo.signOut()
                        onSignOut()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.ExitToApp,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "退出登录",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { victim ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账号") },
            text = {
                Text(
                    "将从本机删除「${victim.title}」的登录态，云端会话不受影响。" +
                        "下次使用需要重新收验证码登录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    repo.removeAccount(victim.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    (updateState as? UpdateCheck.Available)?.let { available ->
        AlertDialog(
            onDismissRequest = { updateState = UpdateCheck.UpToDate },
            title = { Text("发现新版本 ${available.info.version}") },
            text = {
                Text(
                    available.info.notes.ifBlank { "前往 GitHub Releases 下载新版安装包。" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        openUrl(context, available.info.apkUrl ?: available.info.pageUrl)
                        updateState = UpdateCheck.UpToDate
                    }
                ) { Text("去下载") }
            },
            dismissButton = {
                TextButton(onClick = { updateState = UpdateCheck.UpToDate }) { Text("稍后") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CardGroup 风格私有组件
// ─────────────────────────────────────────────────────────────────────────────

/** 一组设置项的标题，放在组容器上方。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/**
 * 一组共享大圆角容器的设置项集合。
 * 圆角 extraLargeIncreased，颜色 surfaceContainerLow，组间距由外层 LazyColumn 提供。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        GroupLabel(title)
        Surface(
            shape = MaterialTheme.shapes.extraLargeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column { content() }
        }
    }
}

/**
 * 组内一行：整行可点，上下 16dp、左右 20dp 内边距。
 * onClick 为 null 时不可点。
 */
@Composable
private fun GroupRow(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        content()
    }
}

/** 组内行间距 1dp 分割线，颜色 outlineVariant 50% 透明。 */
@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

/** 标题 + 副标题竖排（无图标时用）。 */
@Composable
private fun GroupTextColumn(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 标题 + 副标题 + 右侧 Switch 的一整行。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    GroupRow(onClick = { onCheckedChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupTextColumn(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 标题 + 副标题 + 一排可选项 chips。 */
@Composable
private fun <T> ChipRow(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    GroupRow {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupTextColumn(title = title, subtitle = subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    val active = selected == value
                    Surface(
                        shape = MaterialTheme.shapes.largeIncreased,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable { onSelect(value) }
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 签到按钮：FilledTonalButton，形状 largeIncreased。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckinButton(loading: Boolean, checkedIn: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !loading && !checkedIn,
        shape = MaterialTheme.shapes.largeIncreased
    ) {
        if (loading) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        } else if (checkedIn) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            when {
                loading -> "处理中…"
                checkedIn -> "今日已签到"
                else -> "签到"
            }
        )
    }
}

/** 检查更新的界面状态。Failed 与 UpToDate 必须分开，否则网络不通会被显示成「已是最新」。 */
private sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Available(val info: ReleaseInfo) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/** 用系统浏览器打开链接；没有可用应用时提示，而不是静默失败。 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
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
