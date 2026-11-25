package cn.cloudbuddy.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.CustomModel
import cn.cloudbuddy.app.data.LlmProvider
import cn.cloudbuddy.app.data.ThinkingEffort

/**
 * 「我的模型」列表 + 端点候选池。
 *
 * 为什么要有这一层：`GET /models` 返回的是一份**目录**，不是配置。真实端点动辄
 * 几十上百个模型，全平铺进选择器没人找得到；而每次拉取都覆盖的话，用户改过的
 * 显示名和参数就白改了。所以照开源客户端（RikkaHub）的做法切成两层：
 * 候选池只读，勾选后进入「我的模型」，后者才是会话里能选的那几个。
 *
 * 模型挂在提供商（[LlmProvider]）下面：拉取、手动添加都发生在「当前选中」的
 * 提供商上；列表条目按选择键（providerId/modelId）唯一，不同提供商下的同名模型
 * 互不干扰。
 */
@Composable
fun CustomModelManager(
    models: List<CustomModel>,
    providers: List<LlmProvider>,
    selectedId: String,
    candidates: List<String>,
    /** 全局思考强度，用于展示「跟随全局（中）」这类回退说明。 */
    globalEffort: String,
    /** 全局历史轮数，同上。 */
    globalTurns: Int,
    fetching: Boolean,
    error: String?,
    onFetch: (String) -> Unit,
    onClearCandidates: () -> Unit,
    onAdd: (List<String>, String) -> Unit,
    onRemove: (String) -> Unit,
    onUpdate: (String, CustomModel) -> Unit,
    onSelect: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    // 拉取/添加发生在哪个提供商上。默认取第一个启用的；没有提供商时为空串（旧入口）。
    var activeProviderId by remember { mutableStateOf(providers.firstOrNull { it.enabled }?.id ?: "") }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }

    // 提供商列表变化（删除了当前选中的）时兜底。
    if (activeProviderId.isNotBlank() && providers.none { it.id == activeProviderId }) {
        activeProviderId = providers.firstOrNull()?.id ?: ""
    }

    // 拉取成功就把选择器弹出来：用户点「拉取」的下一步必然是「挑几个」，让这一步自动发生。
    // 判断依据是「本次拉取结束了且池子非空」，不是「池子变长了」——后者在重复拉同一个
    // 端点（清单没变化）时不会触发，用户会觉得按钮没反应。
    var awaitingPicker by remember { mutableStateOf(false) }
    LaunchedEffect(fetching, candidates.size) {
        if (awaitingPicker && !fetching && candidates.isNotEmpty()) {
            pickerOpen = true
            awaitingPicker = false
        }
    }
    val startFetch = {
        awaitingPicker = true
        onFetch(activeProviderId)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "我的模型",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        if (fetching) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        TextButton(onClick = startFetch, enabled = !fetching && activeProviderId.isNotBlank()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (fetching) "拉取中…" else "拉取")
        }
        TextButton(
            onClick = { pickerOpen = true },
            enabled = activeProviderId.isNotBlank(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("添加")
        }
    }

    // 多个提供商时先选一个：拉取与添加都落在它名下。照 RikkaHub 的
    // Provider 选择器做法，横向一排可点的小胶囊。
    if (providers.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            providers.forEach { p ->
                val active = p.id == activeProviderId
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.clickable {
                        activeProviderId = p.id
                        // 换提供商等于换端点：候选池是上一个端点的，清掉。
                        onClearCandidates()
                    }
                ) {
                    Text(
                        p.label + if (p.enabled) "" else "（停用）",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (models.isEmpty()) {
        Text(
            "还没有模型。选好提供商后点「拉取」列一份清单，勾几个进来；端点不提供模型列表时，" +
                "也可以在添加框里直接手填模型 ID。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // 只先组合前几条：这个列表是设置页里唯一「一次全画」的长列表，
        // 模型攒到几十个时整块 item 的测量成本会拖慢页面滚动。其余按需展开。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = if (showAll) models else models.take(COLLAPSED_MODELS)
            shown.forEach { model ->
                val expanded = expandedId == model.selKey
                ModelRow(
                    model = model,
                    provider = providers.firstOrNull { it.id == model.providerId },
                    selected = model.selKey == selectedId,
                    expanded = expanded,
                    globalEffort = globalEffort,
                    globalTurns = globalTurns,
                    onSelect = { onSelect(model.selKey) },
                    onToggleExpand = { expandedId = if (expanded) null else model.selKey },
                    onUpdate = { onUpdate(model.selKey, it) },
                    onRemove = {
                        if (expandedId == model.selKey) expandedId = null
                        onRemove(model.selKey)
                    }
                )
            }
            if (!showAll && models.size > COLLAPSED_MODELS) {
                TextButton(
                    onClick = { showAll = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        "显示全部 ${models.size} 个",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    if (pickerOpen) {
        CandidatePicker(
            candidates = candidates,
            models = models.filter { it.providerId == activeProviderId },
            fetching = fetching,
            onFetch = startFetch,
            onManualAdd = { id -> onAdd(listOf(id), activeProviderId) },
            onConfirm = {
                onAdd(it, activeProviderId)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false }
        )
    }
}

@Composable
private fun ModelRow(
    model: CustomModel,
    provider: LlmProvider?,
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
                            // 提供商标签放最前：同名模型挂在两个提供商下时靠它区分。
                            if (provider != null) append(provider.label + " · ")
                            if (model.title != model.id) append(model.id)
                            if (model.reasoning) {
                                if (isNotEmpty()) append(" · ")
                                append("思考")
                            }
                            if (model.customized) {
                                if (isNotEmpty()) append(" · ")
                                append("已自定义")
                            }
                        }.ifBlank { model.id },
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
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 0.dp))
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

                    Text("思考强度", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 第一项是「跟随全局」：默认就该是它，用户只为个别模型破例。
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

@Composable
private fun CandidatePicker(
    candidates: List<String>,
    models: List<CustomModel>,
    fetching: Boolean,
    onFetch: () -> Unit,
    onManualAdd: (String) -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var manual by remember { mutableStateOf("") }
    // 已加入的也要显示，但勾不动——否则用户会以为「端点列表少了一截」。
    // 这里的 models 已由调用方按当前提供商过滤，按裸 ID 判断重复即可。
    val added = remember(models) { models.map { it.id }.toSet() }
    val visible = remember(candidates, query) {
        val list = candidates.map { CustomModel(it) }
        CustomModel.filter(list, query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从端点添加模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 手动输入永远可用：不少自建 vLLM 不提供 /models，这是唯一入口。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text("手动输入模型 ID") },
                        placeholder = { Text("deepseek-chat") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val id = manual.trim()
                            if (id.isNotBlank()) {
                                onManualAdd(id)
                                manual = ""
                            }
                        },
                        enabled = manual.isNotBlank()
                    ) { Text("加入") }
                }
                if (candidates.isEmpty()) {
                    Text(
                        "候选池还是空的。点下面的按钮拉一次端点清单——只在这一次请求，不会后台自动拉。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onFetch, enabled = !fetching) {
                            Text(if (fetching) "拉取中…" else "拉取模型列表")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索") },
                        placeholder = { Text("按模型 ID 或名称过滤，空格分隔多个关键词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(visible, key = { it.id }) { m ->
                            val exists = m.id in added
                            val checked = m.id in picked
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !exists) {
                                        picked = if (checked) picked - m.id else picked + m.id
                                    }
                            ) {
                                Checkbox(
                                    checked = exists || checked,
                                    enabled = !exists,
                                    onCheckedChange = {
                                        picked = if (it) picked + m.id else picked - m.id
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        m.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (exists) {
                                        Text(
                                            "已在我的模型里",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "共 ${candidates.size} 个，已选 ${picked.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked.toList()) },
                enabled = picked.isNotEmpty()
            ) { Text("加入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 折叠状态下先画几个模型：再多就交给「显示全部」。 */
private const val COLLAPSED_MODELS = 8
