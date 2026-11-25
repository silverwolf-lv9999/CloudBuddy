package cn.cloudbuddy.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.BuildConfig
import cn.cloudbuddy.app.R
import cn.cloudbuddy.app.data.Account
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.CustomModelConfig
import cn.cloudbuddy.app.data.Edition
import cn.cloudbuddy.app.data.EndpointResolver
import cn.cloudbuddy.app.data.FoldMode
import cn.cloudbuddy.app.data.ReleaseInfo
import cn.cloudbuddy.app.data.ThinkingEffort
import cn.cloudbuddy.app.data.ThinkingParams
import cn.cloudbuddy.app.data.ThinkingStyle
import cn.cloudbuddy.app.data.UpdateChecker
import cn.cloudbuddy.app.ui.components.ConnectorCard
import cn.cloudbuddy.app.ui.components.McpCard
import cn.cloudbuddy.app.ui.components.ProviderManager
import cn.cloudbuddy.app.ui.components.SkillsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================ 账号与额度 ============================

@Composable
internal fun AccountSettingsPage(ui: AppUiState, repo: BuddyRepository, onBack: () -> Unit) {
    var pendingDelete by remember { mutableStateOf<Account?>(null) }

    SettingsPageScaffold("账号与额度", onBack) { padding ->
        SettingsList(padding) {
            title("账号", Icons.Filled.Person)
            item {
                SettingCard {
                    ui.accounts.forEach { account ->
                        AccountRow(
                            account = account,
                            active = account.id == ui.activeAccountId,
                            onSwitch = { repo.switchAccount(account.id) },
                            onDelete = { pendingDelete = account }
                        )
                        Divider()
                    }
                    SettingRow(
                        title = "添加账号",
                        subtitle = "登录另一个账号；两个账号的登录态各自保留，切换不用重新收验证码",
                        trailing = {
                            IconButton(onClick = { repo.addAccount() }) {
                                Icon(
                                    Icons.Filled.Add,
                                    "添加账号",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }

            title("套餐额度", Icons.Filled.Star)
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 余额和签到留在设置首页：这一页只放首页放不下的明细，
                        // 两处都写同一句话不叫"更完整"，叫重复。
                        if (ui.usage.isEmpty()) {
                            Text(
                                "没有查到额度包。可能是账号本来就没配额，或这次拉取失败——" +
                                    "回设置首页点右上角刷新再试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            ui.usage.forEach { pack ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pack.name, style = MaterialTheme.typography.bodyMedium)
                                        pack.expireAt?.takeIf { it.isNotBlank() }?.let { date ->
                                            Text(
                                                "有效期至 " + date.take(10),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "剩 ${pack.left.toInt()} / ${pack.total.toInt()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            title("云端环境", Icons.Filled.Cloud)
            item {
                SettingCard {
                    SettingRow(
                        title = "服务站点",
                        subtitle = repo.settings.baseUrl,
                        trailing = { Text("官方", style = MaterialTheme.typography.labelSmall) }
                    )
                    Divider()
                    SettingRow(
                        title = "当前会话模型",
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

            item { Spacer(Modifier.size(24.dp)) }
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
}

/** 账号列表里的一行：头像首字 + 名字 + 手机号，右侧是「当前」标记或切换/删除。 */
@Composable
private fun AccountRow(
    account: Account,
    active: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !active, onClick = onSwitch)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    account.title.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(account.title, style = MaterialTheme.typography.titleMedium)
            if (account.subtitle.isNotBlank()) {
                Text(
                    account.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (active) {
            Text(
                "当前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            IconButton(onClick = onSwitch) {
                Icon(Icons.Filled.SwapHoriz, "切换到该账号", Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                "删除账号",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun CheckinButton(loading: Boolean, checkedIn: Boolean, onClick: () -> Unit) {
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

/** 签到副标题：连续天数 / 今日所得 / 可得积分。 */
internal fun checkinHint(ui: AppUiState): String? {
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

// ============================ 自定义 API ============================

@Composable
internal fun CustomApiSettingsPage(ui: AppUiState, repo: BuddyRepository, onBack: () -> Unit) {
    // 精简版没有这一页的能力。入口已经在首页隐藏，这里再拦一道：
    // 万一从旧状态或深链进来，看到的是一句说明，不是半张空表单。
    if (!Edition.full) {
        SettingsPageScaffold("本机模型", onBack) { padding ->
            SettingsList(padding) {
                title("本版本不含此功能", Icons.Filled.Info)
                item {
                    SettingCard {
                        Text(
                            Edition.CORE_ONLY_CLOUD,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        return
    }

    // 当前模型的选择键（providerId/modelId 或旧数据的裸 ID），会话默认值。
    var customModelId by remember { mutableStateOf(repo.settings.customModelId) }
    var customTemperature by remember { mutableStateOf(repo.settings.customTemperature) }
    var customMaxIter by remember { mutableStateOf(repo.settings.customMaxIterations.toString()) }
    var customCtxTurns by remember { mutableStateOf(repo.settings.customContextTurns.toString()) }
    var customPrompt by remember { mutableStateOf(repo.settings.customSystemPrompt) }
    var thinkingEffort by remember { mutableStateOf(repo.settings.customThinkingEffort) }
    var thinkingStyle by remember { mutableStateOf(repo.settings.customThinkingStyle) }

    // 三级页：提供商详情。模型管理全部发生在详情页里（配置 + 拉取列表 + 勾选），
    // 这个列表页只留入口与摘要。
    var detailProviderId by remember { mutableStateOf<String?>(null) }
    val detailProvider = ui.llmProviders.firstOrNull { it.id == detailProviderId }
    // 详情页内按返回键回列表页，而不是把整个「本机模型」页退出。
    BackHandler(enabled = detailProvider != null) { detailProviderId = null }
    // 进详情页顺手刷新一次模型能力目录（models.dev），静默：目录只影响能力自动判断。
    LaunchedEffect(detailProviderId != null) {
        if (detailProviderId != null) runCatching { repo.refreshModelCatalog() }
    }

    // 这一页只管**本机自定义 API** 的参数。引擎切换（新会话用云端还是本机）不在这里：
    // 它决定的是「这个 App 走哪套后端」，不是某一套后端的配置，放在首页单独一栏。
    if (detailProvider != null) {
        SettingsPageScaffold(
            detailProvider.label.ifBlank { "提供商详情" },
            onBack = { detailProviderId = null }
        ) { padding ->
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    SettingCard {
                        cn.cloudbuddy.app.ui.components.ProviderDetailPage(
                            provider = detailProvider,
                            models = ui.customModels.filter { it.providerId == detailProvider.id },
                            selectedId = customModelId,
                            globalEffort = thinkingEffort,
                            globalTurns = repo.settings.customContextTurns,
                            onUpdate = { p ->
                                repo.upsertProvider(p)
                                customModelId = repo.settings.customModelId
                            },
                            onRemove = {
                                repo.removeProvider(detailProvider.id)
                                customModelId = repo.settings.customModelId
                                // detailProvider 变 null 后自动落回列表页
                                detailProviderId = null
                            },
                            onAddModels = { ids ->
                                repo.addCustomModels(ids, detailProvider.id)
                                customModelId = repo.settings.customModelId
                            },
                            onRemoveModel = { key ->
                                repo.removeCustomModel(key)
                                customModelId = repo.settings.customModelId
                            },
                            onUpdateModel = { key, m ->
                                repo.updateCustomModel(key, m)
                                customModelId = repo.settings.customModelId
                            },
                            onSelect = { id ->
                                customModelId = id
                                repo.settings.customModelId = id
                            },
                            onFetch = { p -> repo.fetchProviderModels(p) }
                        )
                    }
                }
            }
        }
        return
    }

    SettingsPageScaffold("本机模型", onBack) { padding ->
        SettingsList(padding) {
            title("提供商", Icons.Filled.Extension, hint = "点一个提供商进去配地址、Key，并拉取它的模型列表")
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 一个提供商一个端点。编辑与模型管理都在详情页里：
                        // 模型列表是在提供商设置里拉的，不是在别处手填。
                        cn.cloudbuddy.app.ui.components.ProviderManager(
                            providers = ui.llmProviders,
                            models = ui.customModels,
                            onOpen = { p -> detailProviderId = p.id },
                            onAdd = { p ->
                                val id = repo.upsertProvider(p)
                                detailProviderId = id
                            },
                            onRemove = { p ->
                                repo.removeProvider(p.id)
                                customModelId = repo.settings.customModelId
                            }
                        )
                        Text(
                            "Key 只存在本机，不上传、不写日志、不进沙箱工作区。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            title("请求参数", Icons.Filled.Tune)
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 把归一化后的真实请求地址摊开给用户看：填了 /v1 也不会拼成
                        // /v1/v1/chat/completions，这里显示的就是最终会打的那一条。
                        // 按当前选中模型实际用的端点展示：多提供商后没有统一端点了。
                        val previewBase = remember(ui.customModels, ui.llmProviders, customModelId) {
                            ui.customModels.firstOrNull { it.selKey == customModelId }?.let { m ->
                                ui.llmProviders.firstOrNull { it.id == m.providerId }?.baseUrl
                            } ?: repo.settings.customBaseUrl
                        }
                        Text(
                            "请求将发往：" + EndpointResolver.previewPath(previewBase),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Key 只存在本机，不上传、不写日志、不进沙箱工作区。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Divider()

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "采样温度",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "%.1f".format(customTemperature),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = customTemperature,
                            onValueChange = {
                                customTemperature = it
                                repo.settings.customTemperature = it
                            },
                            valueRange = 0f..2f,
                            steps = 19,
                        )
                        Text(
                            "低更稳定、适合改代码；高更发散、适合写作。默认 0.3。" +
                                "注意：思考模式开着时，部分服务端会忽略这个值。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            title("思考强度", Icons.Filled.Psychology)
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "强度",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                ThinkingEffort.of(thinkingEffort).label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThinkingEffort.entries.forEach { e ->
                                val active = e.key == thinkingEffort
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable {
                                        thinkingEffort = e.key
                                        repo.settings.customThinkingEffort = e.key
                                    }
                                ) {
                                    Text(
                                        e.label,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Text(
                            "参数写法",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThinkingStyle.entries.forEach { st ->
                                val active = st.key == thinkingStyle
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable {
                                        thinkingStyle = st.key
                                        repo.settings.customThinkingStyle = st.key
                                    }
                                ) {
                                    Text(
                                        st.label,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(
                            "「思考强度」不是一个标准字段，各家写法不同：DeepSeek / 智谱 / Kimi " +
                                "要 thinking:{type:enabled}，通义 / 硅基流动要 enable_thinking，" +
                                "OpenAI / xAI 只认 reasoning_effort。选「自动」按接口地址猜，" +
                                "猜错了改这里就行。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // describe 要按**这条模型实际会发的字段**来说：某个模型单独设了强度，
                        // 这里还念全局值，用户会以为自己的设置没生效。
                        val effectiveEffort = ui.customModels
                            .firstOrNull { it.id == customModelId }?.thinkingEffort
                            ?.takeIf { it.isNotBlank() } ?: thinkingEffort
                        val overridden = effectiveEffort != thinkingEffort
                        Text(
                            ThinkingParams.describe(
                                repo.settings.customModel()?.baseUrl ?: "",
                                thinkingStyle, effectiveEffort,
                                repo.settings.customModel()?.modelId ?: ""
                            ) +
                                if (overridden) {
                                    "\n（该模型单独设了强度：${ThinkingEffort.of(effectiveEffort).label}）"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            title("上下文", Icons.Filled.Tune)
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = customMaxIter,
                                onValueChange = { v ->
                                    customMaxIter = v.filter { it.isDigit() }.take(3)
                                    customMaxIter.toIntOrNull()?.let { repo.settings.customMaxIterations = it }
                                },
                                label = { Text("最大迭代") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customCtxTurns,
                                onValueChange = { v ->
                                    customCtxTurns = v.filter { it.isDigit() }.take(2)
                                    customCtxTurns.toIntOrNull()?.let { repo.settings.customContextTurns = it }
                                },
                                label = { Text("历史轮数") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "最大迭代：单轮里「推理 + 工具调用」的次数上限，长任务需要更大。" +
                                "历史轮数：每轮回放给模型的历史上限，越大越连贯也越费 token。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            title("系统提示词", Icons.Filled.Person)
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "自定义系统提示词",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (customPrompt.isNotBlank()) {
                                TextButton(onClick = {
                                    customPrompt = ""
                                    repo.settings.customSystemPrompt = ""
                                }) { Text("恢复默认") }
                            }
                        }
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = {
                                customPrompt = it
                                repo.settings.customSystemPrompt = it
                            },
                            placeholder = {
                                Text(
                                    "留空用内置默认：云端 Linux 沙箱里的编程助手，中文、简洁、" +
                                        "先看再改。填了之后这段会替换掉默认的角色与回答风格部分。"
                                )
                            },
                            minLines = 4,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "沙箱环境与工具使用规则会固定追加在末尾（路径、edit_file 的匹配要求等），" +
                                "不用自己写，也别把它们删掉——删了模型会开始猜路径。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

// ============================ 外观与对话 ============================

@Composable
internal fun AppearanceSettingsPage(ui: AppUiState, repo: BuddyRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(repo.settings.themeMode) }
    var dynamic by remember { mutableStateOf(repo.settings.dynamicColor) }
    var showThoughts by remember { mutableStateOf(repo.settings.showThoughts) }
    var enterSend by remember { mutableStateOf(repo.settings.enterSend) }
    var historyTurns by remember { mutableStateOf(repo.settings.historyTurns) }

    SettingsPageScaffold("外观与对话", onBack) { padding ->
        SettingsList(padding) {
            title("外观", Icons.Filled.Palette)
            item {
                SettingCard {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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

            title("对话", Icons.Filled.Tune)
            item {
                SettingCard {
                    ChipSetting(
                        title = "折叠方式",
                        subtitle = "整轮折叠：除最终回答外全收起；只折过程：正文保留，思考与工具调用收成一条",
                        options = listOf(
                            FoldMode.WHOLE.key to "整轮折叠",
                            FoldMode.BODY.key to "只折思考与工具"
                        ),
                        selected = ui.foldMode.key
                    ) { repo.setFoldMode(FoldMode.of(it)) }
                    Divider()
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
                    SettingRow(
                        title = "回车发送消息",
                        subtitle = if (enterSend) "回车直接发送" else "回车换行；发送请点右下角按钮",
                        trailing = {
                            Switch(checked = enterSend, onCheckedChange = {
                                enterSend = it
                                repo.settings.enterSend = it
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
                    // 「历史加载超时」不在这里：它只影响云端 ACP 回放（本机会话直接读本地文件，
                    // 不存在回放），所以归到「云端能力」页，跟着云端设置走。
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

// ============================ 云端能力 ============================

/**
 * 云端 agent 的能力配置：技能、连接器、MCP 服务器，外加云端历史回放。
 *
 * 这三样**只对云端会话生效**——它们是账号里的资产，随 `session/new` 下发给云端
 * agent 执行；本机会话跑在自己的接口上，拿不到也不该假装拿得到。所以它们单独成页，
 * 页首把那句边界写在最显眼的地方，而不是让用户在本机会话里翻半天开关。
 */
@Composable
internal fun CloudSettingsPage(ui: AppUiState, repo: BuddyRepository, onBack: () -> Unit) {
    var historyTimeout by remember { mutableStateOf(repo.settings.historyTimeoutSec) }

    SettingsPageScaffold("云端能力", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingCard {
                    Text(
                        "下面这些由云端 agent 使用，随会话下发给服务端：只在「云端模型」的会话里生效。" +
                            "本机会话（自定义 API）走你自己的接口与本机 agent，用的是沙箱工具，不读这里的配置。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            title("技能", Icons.Filled.Extension)
            item { SkillsCard(ui, repo) }

            title("连接器", Icons.Filled.Hub)
            item { ConnectorCard(ui, repo) }

            title("MCP 服务器", Icons.Filled.Hub)
            item { McpCard(ui, repo) }

            title("历史回放", Icons.Filled.Cloud)
            item {
                SettingCard {
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

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

// ============================ 关于 ============================

/** 检查更新的界面状态。Failed 与 UpToDate 必须分开，否则网络不通会被显示成「已是最新」。 */
private sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Available(val info: ReleaseInfo) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

@Composable
internal fun AboutSettingsPage(repo: BuddyRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateCheck>(UpdateCheck.Idle) }

    fun checkUpdate() {
        if (updateState is UpdateCheck.Checking) return
        updateState = UpdateCheck.Checking
        scope.launch {
            updateState = try {
                // 传档位：三包共用同一个 Release，必须挑属于自己档位的 APK，
                // 抓第一个下回来会静默换档（base↔full）或装不上（plus 换包名）。
                val info = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest(Edition.name) }
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

    SettingsPageScaffold("关于", onBack) { padding ->
        SettingsList(padding) {
            title("关于", Icons.Filled.Info)
            item {
                SettingCard {
                    Column {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "版本 ${BuildConfig.VERSION_NAME} · ${Edition.label} · 第三方客户端\n${BuildConfig.APPLICATION_ID}",
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
                        Divider()
                        AboutRow(
                            title = "检查更新",
                            subtitle = when (val s = updateState) {
                                UpdateCheck.Idle -> "从 GitHub Releases 获取 ${Edition.label}的最新版本"
                                UpdateCheck.Checking -> "正在连接 GitHub…"
                                UpdateCheck.UpToDate -> "当前 ${BuildConfig.VERSION_NAME} 已是最新"
                                is UpdateCheck.Available -> "发现新版本 ${s.info.version}，点击查看"
                                is UpdateCheck.Failed -> "检查失败：${s.message}"
                            },
                            trailing = when (val s = updateState) {
                                UpdateCheck.Idle -> "检查"
                                UpdateCheck.Checking -> "…"
                                UpdateCheck.UpToDate -> "已是最新"
                                is UpdateCheck.Available -> "v${s.info.version}"
                                is UpdateCheck.Failed -> "重试"
                            },
                            accent = updateState is UpdateCheck.Available,
                            onClick = {
                                val current = updateState
                                when {
                                    current is UpdateCheck.Available ->
                                        openUrl(context, current.info.apkUrl ?: current.info.pageUrl)
                                    else -> checkUpdate()
                                }
                            }
                        )
                        Divider()
                        AboutRow(
                            title = "GitHub 仓库",
                            subtitle = UpdateChecker.REPO_SLUG,
                            trailing = "打开",
                            onClick = { openUrl(context, UpdateChecker.REPO_URL) }
                        )
                        Divider()
                        AboutRow(
                            title = "提交反馈",
                            subtitle = "遇到问题或有想法？在 GitHub Issues 里告诉我们",
                            trailing = "打开",
                            onClick = { openUrl(context, "https://github.com/${UpdateChecker.REPO_SLUG}/issues") }
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
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

/** 关于板块里的一行：标题 + 说明 + 右侧状态文字，整行可点。 */
@Composable
private fun AboutRow(
    title: String,
    subtitle: String,
    trailing: String,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            trailing,
            style = MaterialTheme.typography.labelLarge,
            color = if (accent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
