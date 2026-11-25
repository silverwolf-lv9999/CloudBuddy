package cn.cloudbuddy.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cn.cloudbuddy.app.data.CustomModel
import cn.cloudbuddy.app.data.CustomModelCatalog
import cn.cloudbuddy.app.data.EndpointResolver
import cn.cloudbuddy.app.data.LlmProvider
import cn.cloudbuddy.app.data.ModelRegistry
import cn.cloudbuddy.app.data.ThinkingEffort

/**
 * 提供商列表：一个提供商一张卡，点击进详情页。
 *
 * 照 RikkaHub 的 SettingProviderPage：列表只负责「看 + 入口」，编辑与模型管理
 * 全部发生在详情页（[ProviderDetailPage]）。模型数直接标在卡上，配没配一眼可见。
 */
@Composable
fun ProviderManager(
    providers: List<LlmProvider>,
    /** 全部自定义模型（每个提供商卡上标自己的数量）。 */
    models: List<CustomModel>,
    onOpen: (LlmProvider) -> Unit,
    onAdd: (LlmProvider) -> Unit,
    onRemove: (LlmProvider) -> Unit,
) {
    var removing by remember { mutableStateOf<LlmProvider?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "提供商",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = {
                // 新建即进详情：RikkaHub 的 AddButton 也是「只建一个空壳然后跳详情」，
                // 表单在详情页里填，列表页不做编辑。
                onAdd(LlmProvider(id = LlmProvider.newId(), name = "", baseUrl = "", apiKey = ""))
            }
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("添加")
        }
    }

    if (providers.isEmpty()) {
        Text(
            "还没有提供商。一个提供商就是一个 OpenAI 兼容端点（地址 + Key），" +
                "可以同时配 DeepSeek 官方、中转站、自建 vLLM 等多个，每个下面挂各自的模型。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { p ->
                val count = models.count { it.providerId == p.id }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (p.enabled) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(p) }
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 站点图标（favicone.com 按 host 取真实 logo，照 RikkaHub 的 Favicon），
                        // 拉不到回落品牌图标 / 字母头像。
                        ProviderIcon(
                            providerName = p.label,
                            baseUrl = p.baseUrl,
                            size = 36.dp,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                p.label.ifBlank { "未命名提供商" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                EndpointResolver.previewPath(p.baseUrl),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Tag(
                                    text = if (p.enabled) "已启用" else "已停用",
                                    container = if (p.enabled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.error
                                )
                                Tag(
                                    text = "$count 个模型",
                                    container = MaterialTheme.colorScheme.secondaryContainer
                                )
                            }
                        }
                        IconButton(
                            onClick = { removing = p },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    removing?.let { target ->
        AdaptiveDialog(
            onDismissRequest = { removing = null },
            title = "删除提供商「${target.label.ifBlank { "未命名" }}」？",
            confirmButton = {
                TextButton(onClick = {
                    onRemove(target)
                    removing = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }
        ) {
            Text(
                "它名下的模型会一起删除，使用这些模型的会话将退回云端模型。此操作不可撤销。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun Tag(text: String, container: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================ 小屏自适应 ============================
// 这个文件里的界面要能在 410×502 的矩形手表上用：常见密度（1.5~2.0）下宽度
// 只有 205~273dp、高度 251~335dp，手机一般 ≥360dp 宽。判定不是二值化布局，
// 只处理两类必炸的问题：①对话框默认宽度按手机算（内容会被 clamp 贴边、按钮
// 挤变形）；②固定高度的弹窗列表（320dp）直接撑爆屏高。其余布局本身是
// weight + fillMaxWidth 的，天然随宽度缩。

/** 小屏判定：手表 / 分屏窄窗 / 折叠屏窄态都落进来。 */
@Composable
private fun isCompactScreen(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp < 340
}

/**
 * 自适应对话框：关掉平台默认宽度，自己控制「满宽 + 安全边距 + 宽度上限」。
 * M3 默认 AlertDialog 有 280dp 起的最小宽度——在 205~273dp 宽的表上会顶满
 * 屏幕边缘，两个按钮挤在一行还可能被裁；这里的内容边距小屏减半，
 * 确认/取消按钮行自绘（M3 的 slots 排版不受影响，行为一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdaptiveDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = isCompactScreen()
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(
                        horizontal = if (compact) 10.dp else 24.dp,
                        vertical = 16.dp
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    content()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton()
                        Spacer(Modifier.width(6.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * 提供商详情页，照 RikkaHub 的 SettingProviderDetailPage 拆两块：
 *
 * - 配置：启用 / 名称 / Key / 地址，改动即存（字段少，不值得再加一个保存按钮）。
 * - 模型：进页**自动**拉一次 `/models`（配置变了重拉，右上可手动刷新）；
 *   「从列表添加」弹窗里勾选，已加入的标勾、支持全部加入；端点不提供 /models
 *   时走手动输入。已加入的模型一张卡一行，展开改显示名 / 思考 / 参数覆盖。
 */
@Composable
fun ProviderDetailPage(
    provider: LlmProvider,
    /** 该提供商名下已加入的模型。 */
    models: List<CustomModel>,
    /** 当前会话选中的选择键（providerId/modelId），用于标「当前使用」。 */
    selectedId: String,
    globalEffort: String,
    globalTurns: Int,
    onUpdate: (LlmProvider) -> Unit,
    onRemove: () -> Unit,
    onAddModels: (List<String>) -> Unit,
    onRemoveModel: (String) -> Unit,
    onUpdateModel: (String, CustomModel) -> Unit,
    onSelect: (String) -> Unit,
    /** 拉取该提供商的远端模型清单（repo.fetchProviderModels）。 */
    onFetch: suspend (LlmProvider) -> CustomModelCatalog.Result,
) {
    var removing by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    // 请求头编辑框的原始文本：Map 是解析结果，文本才是编辑状态（半行、空行都要能先敲着）。
    var headersText by remember(provider.id) { mutableStateOf(LlmProvider.headersToText(provider.headers)) }

    // 远端清单是该页的本地状态：拉取成功才显示数量，失败保留旧清单并显示错误。
    var remoteModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // 自动拉取：地址或 Key 变了就重拉一次，行为与 RikkaHub 的 produceState(listKey) 一致。
    // 空配置不拉（catalog 会报「先填地址」），等用户填完触发重compose 自然再试。
    LaunchedEffect(provider.baseUrl, provider.apiKey, refreshTick) {
        if (provider.baseUrl.isBlank() || provider.apiKey.isBlank()) {
            error = if (provider.baseUrl.isBlank()) "先填接口地址" else "先填 API Key"
            return@LaunchedEffect
        }
        loading = true
        error = null
        val r = onFetch(provider)
        loading = false
        if (r.ok) {
            remoteModels = r.models
        } else {
            error = r.error
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(if (isCompactScreen()) 8.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("配置", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启用", style = MaterialTheme.typography.bodySmall)
                Text(
                    "停用后它的模型不再出现在选择器里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = provider.enabled, onCheckedChange = { onUpdate(provider.copy(enabled = it)) })
        }
        OutlinedTextField(
            value = provider.name,
            onValueChange = { onUpdate(provider.copy(name = it)) },
            label = { Text("名称") },
            placeholder = { Text("DeepSeek 官方 / 某某中转") },
            singleLine = true,
            leadingIcon = {
                ProviderIcon(
                    providerName = provider.name,
                    baseUrl = provider.baseUrl,
                    size = 26.dp
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        SecretField(
            value = provider.apiKey,
            onValueChange = { onUpdate(provider.copy(apiKey = it.trim())) },
            label = "API Key"
        )
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onUpdate(provider.copy(baseUrl = it.trim())) },
            label = { Text("接口地址") },
            placeholder = { Text("https://api.deepseek.com 或 https://xxx/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "填不填 /v1 都可以，请求将发往：" + EndpointResolver.previewPath(provider.baseUrl),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        )
        // 请求路径留空走统一规则；个别网关不在 /chat/completions 结尾时才需要动它。
        OutlinedTextField(
            value = provider.chatPath,
            onValueChange = { onUpdate(provider.copy(chatPath = it.trim())) },
            label = { Text("请求路径（留空自动）") },
            placeholder = { Text(EndpointResolver.DEFAULT_PATH) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // 自定义请求头：多行 `Key: Value`，中转站的鉴权 / 归属头从这里加。
        OutlinedTextField(
            value = headersText,
            onValueChange = {
                headersText = it
                onUpdate(provider.copy(headers = LlmProvider.parseHeaders(it)))
            },
            label = { Text("自定义请求头") },
            placeholder = { Text("HTTP-Referer: https://yoursite.com\nX-Title: CloudBuddy") },
            minLines = 1,
            maxLines = 5,
            supportingText = {
                Text("每行一条，格式 Key: Value。随请求一起发送。", style = MaterialTheme.typography.labelSmall)
            },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        // ---- 模型区 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "模型（${models.size}）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            IconButton(
                onClick = { refreshTick++ },
                enabled = !loading && provider.usable,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新拉取列表", modifier = Modifier.size(16.dp))
            }
            // 小屏收成图标按钮：四个字带图标的 TextButton 在 200dp 级宽度的表上
            // 会把标题挤没，图标含义（+）在这里足够明确，弹窗里有完整入口。
            if (isCompactScreen()) {
                IconButton(onClick = { pickerOpen = true }, enabled = !loading, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "从列表添加", modifier = Modifier.size(16.dp))
                }
            } else {
                TextButton(onClick = { pickerOpen = true }, enabled = !loading) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("从列表添加")
                }
            }
        }
        Text(
            when {
                loading -> "正在拉取模型列表…"
                error != null -> "拉取失败：$error"
                remoteModels.isEmpty() -> "还没拉到模型清单。检查地址与 Key，或点右上角刷新；" +
                    "端点不提供 /models 时用「手动添加」。"
                else -> "端点上有 ${remoteModels.size} 个模型，已加入 ${models.size} 个。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                error != null -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        TextButton(
            onClick = { manualOpen = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text("手动添加模型", style = MaterialTheme.typography.labelSmall)
        }

        if (models.isEmpty()) {
            Text(
                "这个提供商下还没有模型。从上面拉到的列表里勾几个进来。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { model ->
                    val expanded = expandedId == model.selKey
                    ModelRow(
                        model = model,
                        selected = model.selKey == selectedId,
                        expanded = expanded,
                        globalEffort = globalEffort,
                        globalTurns = globalTurns,
                        onSelect = { onSelect(model.selKey) },
                        onToggleExpand = { expandedId = if (expanded) null else model.selKey },
                        onUpdate = { onUpdateModel(model.selKey, it) },
                        onRemove = {
                            if (expandedId == model.selKey) expandedId = null
                            onRemoveModel(model.selKey)
                        }
                    )
                }
            }
        }

        HorizontalDivider()

        TextButton(
            onClick = { removing = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text("删除此提供商", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "Key 只存在本机，不上传、不写日志、不进沙箱工作区。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (pickerOpen) {
        RemoteModelPicker(
            remoteModels = remoteModels,
            existingIds = models.map { it.id }.toSet(),
            loading = loading,
            onRefresh = { refreshTick++ },
            onAdd = { ids ->
                if (ids.isNotEmpty()) onAddModels(ids)
            },
            onDismiss = { pickerOpen = false }
        )
    }

    if (manualOpen) {
        ManualAddDialog(
            onAdd = { id ->
                onAddModels(listOf(id))
                manualOpen = false
            },
            onDismiss = { manualOpen = false }
        )
    }

    if (removing) {
        AdaptiveDialog(
            onDismissRequest = { removing = false },
            title = "删除提供商？",
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    onRemove()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } }
        ) {
            Text(
                "它名下的模型会一起删除，使用这些模型的会话将退回云端模型。此操作不可撤销。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 密文输入框：默认遮住，右侧眼睛切换显隐（照 RikkaHub 的 secret textFieldItem）。 */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, modifier = Modifier.size(32.dp)) {
                Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 远端模型选择弹窗：搜索过滤、逐个 +/✓、全部加入。
 * 已加入的模型照样显示但勾不动，和 RikkaHub 的 ModelPicker 一个口径。
 *
 * 小屏处理：不用默认 AlertDialog（280dp 最小宽 + 内容 wrap 高度，在表上
 * 列表会把按钮顶出屏幕），改为 BasicAlertDialog 自控——弹窗最高占屏高九成，
 * 模型列表用 weight 吃剩余空间，搜索框、全部加入和确认按钮行任何屏高都可见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteModelPicker(
    remoteModels: List<String>,
    existingIds: Set<String>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val visible = remember(remoteModels, query) {
        val keywords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (keywords.isEmpty()) remoteModels
        else remoteModels.filter { m -> keywords.all { m.contains(it, ignoreCase = true) } }
    }
    val unselected = visible.count { it !in existingIds && it !in picked }
    val compact = isCompactScreen()
    val maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(
                        horizontal = if (compact) 10.dp else 24.dp,
                        vertical = 16.dp
                    )
                    .heightIn(max = maxBodyHeight)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "端点上的模型（${remoteModels.size}）",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索") },
                            placeholder = { Text("空格分隔多个关键词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                        }
                    }
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("拉取中…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (remoteModels.isEmpty()) {
                        Text(
                            "还没有拿到模型清单。检查配置后点右上角刷新。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        TextButton(
                            onClick = {
                                if (unselected > 0) {
                                    onAdd(visible.filter { it !in existingIds && it !in picked })
                                    picked = emptySet()
                                } else {
                                    // 全都选上了再点一次 = 反选：把过滤范围内的都撤掉
                                    onDismiss()
                                }
                            },
                            enabled = visible.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                if (unselected > 0) "全部加入（$unselected）" else "取消全选",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        // weight：列表吃掉弹窗里剩余的高度，矮屏自动缩短（可滚动），
                        // 高屏放大；按钮行固定在底部永远可见。
                        LazyColumn(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(visible, key = { it }) { id ->
                                val exists = id in existingIds
                                val checked = exists || id in picked
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !exists) {
                                            picked = if (id in picked) picked - id else picked + id
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    BrandIcon(name = id, size = 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        if (checked) Icons.Filled.Check else Icons.Filled.Add,
                                        contentDescription = if (checked) "已加入" else "加入",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (exists) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (exists) {
                                        Text(
                                            "已加入",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (picked.isNotEmpty()) {
                            Text(
                                "已选 ${picked.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                onAdd(picked.toList())
                                onDismiss()
                            },
                            enabled = picked.isNotEmpty()
                        ) { Text("加入所选") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualAddDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var id by remember { mutableStateOf("") }
    AdaptiveDialog(
        onDismissRequest = onDismiss,
        title = "手动添加模型",
        confirmButton = {
            TextButton(
                onClick = { onAdd(id.trim()) },
                enabled = id.isNotBlank()
            ) { Text("加入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        Text(
            "端点不提供 /models 时（不少自建 vLLM 是这样），在这里直接填模型 ID。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("模型 ID") },
            placeholder = { Text("deepseek-chat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 已加入模型的一行：点展开编辑，编辑项与全局模型卡完全一致（显示名 / 思考 /
 * 强度 / 历史轮数 / 系统提示词）。照搬原 CustomModelManager.ModelRow，
 * 去掉提供商标签——详情页里所有模型都是同一个提供商。
 */
@Composable
private fun ModelRow(
    model: CustomModel,
    selected: Boolean,
    expanded: Boolean,
    globalEffort: String,
    globalTurns: Int,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onUpdate: (CustomModel) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 品牌图标（照 RikkaHub 的 AutoAIIcon：按模型名正则匹配品牌图标）
                BrandIcon(name = model.id, size = 22.dp, modifier = Modifier.padding(end = 8.dp))
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect() },
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "当前使用",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        buildString {
                            if (model.title != model.id) append(model.id + " · ")
                            // 目录里查到的上下文长度（models.dev），一眼看出模型规格
                            ModelRegistry.find(model.id)?.contextLength?.takeIf { it >= 1000 }
                                ?.let { append("${it / 1000}K · ") }
                            if (model.vision) append("读图 · ")
                            if (model.reasoning) append("思考 · ")
                            if (!model.tools) append("无工具 · ")
                            if (model.customized) append("已自定义")
                        }.trimEnd('·', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = if (expanded) "收起" else "编辑",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "移除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = model.name,
                        onValueChange = { onUpdate(model.copy(name = it)) },
                        label = { Text("显示名") },
                        placeholder = { Text(model.id) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("这是会思考的模型", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "只影响标签与提示，不改变请求字段",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = model.reasoning,
                            onCheckedChange = { onUpdate(model.copy(reasoning = it)) }
                        )
                    }

                    // 能力开关（照 RikkaHub 的 ModelAbility / Modality 编辑）。
                    // 加入模型时按 ID 猜的初值只求个大概，这里才是权威：猜错随时改。
                    Text("能力", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("工具调用", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "关掉后引擎不下发 tools——不支持 function calling 的端点/纯聊天模型用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = model.tools,
                            onCheckedChange = { onUpdate(model.copy(tools = it)) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("读图（视觉）", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "开着：聊天里的图片直接发给模型；关着：图片只在沙箱里，模型用工具按路径读",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = model.vision,
                            onCheckedChange = { onUpdate(model.copy(vision = it)) }
                        )
                    }

                    Text("思考强度", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val options = listOf("" to "全局·" + ThinkingEffort.of(globalEffort).label) +
                            ThinkingEffort.entries.map { it.key to it.label }
                        options.forEach { (key, label) ->
                            val active = model.thinkingEffort == key
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.clickable {
                                    onUpdate(model.copy(thinkingEffort = key))
                                }
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = model.contextTurns.takeIf { it > 0 }?.toString().orEmpty(),
                        onValueChange = { v ->
                            onUpdate(model.copy(contextTurns = v.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0))
                        },
                        label = { Text("历史轮数（留空跟随全局 $globalTurns）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = model.systemPrompt,
                        onValueChange = { onUpdate(model.copy(systemPrompt = it)) },
                        label = { Text("系统提示词（留空跟随全局）") },
                        placeholder = { Text("只在这个模型上生效") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "系统提示词里的「沙箱与工具规则」永远保留，这里只替换角色与回答风格部分。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
