package cn.cloudbuddy.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.AskAnswer
import cn.cloudbuddy.app.data.AskOption
import cn.cloudbuddy.app.data.AskPick
import cn.cloudbuddy.app.data.AskQuestion
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.Edition
import cn.cloudbuddy.app.data.PendingAttachment
import cn.cloudbuddy.app.data.TurnState
import cn.cloudbuddy.app.ui.components.ChatItem
import cn.cloudbuddy.app.ui.components.MessageRow
import cn.cloudbuddy.app.ui.components.StatePill
import cn.cloudbuddy.app.ui.components.StepsGroup
import cn.cloudbuddy.app.ui.components.windowTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    ui: AppUiState,
    repo: BuddyRepository,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            val loaded = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            loaded.onSuccess { attachments = (attachments + it).distinctBy { a -> a.name } }
            uploading = false
        }
    }
    // 图片入口只出 image/*（GetContent 限定 mime）：发出去的必然是图片，
    // 读图模型才能直发——与「文件」按钮分开就是因为有些格式（可执行、压缩包）
    // 根本没法直接给模型，只能进沙箱用工具读。
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            val loaded = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            loaded.onSuccess { attachments = (attachments + it).distinctBy { a -> a.name } }
            uploading = false
        }
    }

    // 是否跟随到底部：用户上滑查看历史时暂停跟随，回到底部再恢复。
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) -> if (scrolling) autoFollow = !canForward }
    }

    // 一轮对话里，用户提问与最终回答直接显示，中间步骤合成一个可展开的组。
    // 长会话按「轮」开窗：先把尾部窗口裁出来再投影，避免一次铺开几千条消息。
    val pageTurns = repo.settings.historyTurns
    var extraTurns by remember(ui.activeId) { mutableStateOf(0) }
    val window = remember(ui.messages, pageTurns, extraTurns) {
        windowTimeline(
            messages = ui.messages,
            turns = if (pageTurns <= 0) 0 else pageTurns + extraTurns,
            mode = ui.foldMode
        )
    }
    val timeline = window.items
    // 「显示更早」这一项排在列表最前，滚动下标要带上它。
    val headerOffset = if (window.hasMore) 1 else 0

    // 展开更早的历史后，把原来第一条消息重新滚回原来的位置，避免内容整体跳一下。
    // 只以 anchorKey 作为 effect key：用它挂 timeline 会让长列表每帧做一次深比较。
    var anchorKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(anchorKey) {
        val key = anchorKey ?: return@LaunchedEffect
        val index = timeline.indexOfFirst { it.key == key }
        if (index >= 0) {
            listState.scrollToItem(index + headerOffset)
            anchorKey = null
        }
    }

    LaunchedEffect(
        ui.messages.size,
        ui.messages.lastOrNull()?.text?.length,
        ui.state,
        ui.ask,
        window.hasMore
    ) {
        if (timeline.isEmpty() || !autoFollow) return@LaunchedEffect
        // 用瞬时滚动代替 animateScrollToItem：流式输出时动画会被下一个分片反复打断，
        // 每次打断都重新起一段动画，观感就是持续抖动，而且长回答时无法上滑查看。
        val tailIndex = headerOffset + timeline.lastIndex + (if (ui.ask != null) 1 else 0) + 1
        runCatching { listState.scrollToItem(tailIndex) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                title = {
                    Column {
                        Text(
                            ui.activeTitle.ifBlank { if (ui.draft) "新会话" else "云端会话" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "云端沙箱 · /workspace",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    StatePill(ui.state, Modifier.padding(end = 4.dp))
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, "更多")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("折叠中间步骤") },
                                leadingIcon = {
                                    Icon(
                                        if (ui.stepsCollapsed) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                                        null,
                                        Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = ui.stepsCollapsed,
                                        // 点击交给整行处理，避免 Switch 与菜单项各触发一次。
                                        onCheckedChange = null,
                                        modifier = Modifier.scale(0.8f)
                                    )
                                },
                                // 菜单不关：用户要能立刻看到开关的变化，而不是点完就消失。
                                onClick = { repo.setCollapseSteps(!ui.stepsCollapsed) }
                            )
                            DropdownMenuItem(
                                text = { Text("沙箱工作区") },
                                leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; onOpenWorkspace() }
                            )
                            // 精简版没有终端：入口不出现，而不是点进去发现是空的。
                            if (Edition.full) {
                                DropdownMenuItem(
                                    text = { Text("命令行窗口") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp))
                                    },
                                    onClick = { menu = false; onOpenTerminal() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("选择模型") },
                                leadingIcon = { Icon(Icons.Filled.Cloud, null, Modifier.size(18.dp)) },
                                onClick = {
                                    menu = false
                                    showModels = true
                                    // 本机模型清单只在设置页手动拉：打开弹窗就顺手打一次
                                    // 用户的付费端点，既慢又没必要（模型很少变）。
                                    if (ui.engine != AppSettings.ENGINE_CUSTOM) {
                                        scope.launch { repo.refreshModels() }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; renameDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("重新加载对话") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                                onClick = {
                                    menu = false
                                    ui.activeId?.let { id -> scope.launch { repo.reloadConversation(id) } }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除会话") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; deleteDialog = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Composer(
                value = input,
                onValueChange = { input = it },
                attachments = attachments,
                uploading = uploading,
                model = if (ui.engine == AppSettings.ENGINE_CUSTOM) {
                    // 选择键是 providerId/modelId，顶栏只显示模型名（提供商在弹窗里看）。
                    val sel = ui.customModels.firstOrNull { it.selKey == ui.selectedModel }
                    "本机 · " + (sel?.title ?: ui.selectedModel.substringAfterLast('/').ifBlank { "未配置" })
                } else {
                    ui.models.firstOrNull { it.id == ui.selectedModel }?.name
                        ?: ui.selectedModel.ifBlank { "云端默认" }
                },
                running = ui.state == TurnState.RUNNING || ui.sending,
                enterSends = repo.settings.enterSend,
                onPickImage = { imagePicker.launch("image/*") },
                onPickFile = { picker.launch(arrayOf("*/*")) },
                onRemoveAttachment = { name -> attachments = attachments.filterNot { it.name == name } },
                onModelClick = {
                    showModels = true
                    // 本机会话问的是用户自己的端点，云端那份模型清单在这里没有意义；
                    // 它自己那套模型也只在设置页手动拉，这里不自动请求。
                    if (ui.engine != AppSettings.ENGINE_CUSTOM) {
                        scope.launch { repo.refreshModels() }
                    }
                },
                onStop = { repo.stop() },
                onSend = {
                    val text = input.trim()
                    if (text.isEmpty() && attachments.isEmpty()) return@Composer
                    // 引擎按**会话**分流：同一台手机里 A 会话走云端、B 会话走本机，
                    // 互不干扰。默认仍是云端（原有行为）。
                    if (ui.engine == AppSettings.ENGINE_CUSTOM) {
                        repo.sendCustom(text, attachments)
                    } else {
                        repo.send(text, attachments)
                    }
                    input = ""
                    attachments = emptyList()
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ui.loadingConversation -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.4.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (ui.historyProgress > 0) "正在载入历史记录" else "正在连接云端沙箱…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (ui.historyProgress > 0) {
                                "已收到 ${ui.historyProgress} 条 · 加载完直接显示最新状态"
                            } else {
                                "对话越长回放越慢，先到的内容不会逐条冒出来"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    ui.messages.isEmpty() -> Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val localKind = ui.engine == AppSettings.ENGINE_CUSTOM
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (localKind) Icons.Filled.Terminal else Icons.Filled.Cloud,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (localKind) "用你自己的模型开始任务" else "在云端沙箱里开始任务",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (localKind) {
                                "对话在本机推理（不扣积分），工具调用仍在官方沙箱里执行，" +
                                    "产出的文件一样出现在 /workspace。"
                            } else {
                                "任务在官方云端环境执行，产出的文件会出现在沙箱 /workspace 里，" +
                                    "可以直接在「工作区」中浏览、预览和取回。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (window.hasMore) {
                            item(key = "earlier") {
                                EarlierRow(
                                    hiddenTurns = window.hiddenTurns,
                                    hiddenMessages = window.hiddenMessages,
                                    onLoad = {
                                        anchorKey = timeline.firstOrNull()?.key
                                        extraTurns += if (pageTurns > 0) pageTurns else 50
                                    }
                                )
                            }
                        }
                        items(timeline, key = { it.key }, contentType = { item ->
                            // 同类项（都是折叠组 / 都是消息）复用同一套组合缓存，滑动更省。
                            if (item is ChatItem.Steps) "steps" else "single"
                        }) { item ->
                            when (item) {
                                is ChatItem.Single -> MessageRow(
                                    message = item.message,
                                    showThoughts = repo.settings.showThoughts,
                                    finalAnswer = item.finalAnswer
                                )

                                is ChatItem.Steps -> {
                                    // 没手动点过的条目跟随全局开关；手动展开只对本次会话的该条有效。
                                    val expanded = ui.stepsExpanded[item.key] ?: !ui.stepsCollapsed
                                    StepsGroup(
                                        steps = item.steps,
                                        expanded = expanded,
                                        state = ui.state,
                                        showThoughts = repo.settings.showThoughts,
                                        groupKey = item.key,
                                        onToggle = { repo.toggleSteps(item.key, !expanded) }
                                    )
                                }
                            }
                        }
                        ui.ask?.let { ask ->
                            item("ask") {
                                AskCard(ask = ask, onSubmit = { repo.answerQuestion(it) })
                            }
                        }
                        item("tail") { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }

            ui.historyNotice?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            notice,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            ui.activeId?.let { id -> scope.launch { repo.reloadConversation(id) } }
                        }) { Text("重新载入") }
                    }
                }
            }

            ui.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    if (showModels) {
        ModelDialog(
            kind = ui.engine,
            models = ui.models,
            customModels = ui.customModels,
            providers = ui.llmProviders,
            selected = ui.selectedModel,
            loading = ui.modelsLoading,
            error = ui.modelError,
            endpointHint = ui.customModels.firstOrNull { it.selKey == ui.selectedModel }?.let { m ->
                ui.llmProviders.firstOrNull { it.id == m.providerId }?.baseUrl
            } ?: repo.settings.customBaseUrl,
            // 草稿态才有「这条会话还没定型」这回事；已有会话的类型是历史事实。
            canSwitchKind = ui.activeId == null,
            onSwitchKind = { kind ->
                if (kind == AppSettings.ENGINE_CUSTOM) {
                    scope.launch { repo.useCustomEngine() }
                } else {
                    repo.useCloudEngine()
                }
            },
            onRefresh = {
                // 本机的候选清单只能在设置页拉：那里才有地址、Key 与勾选加入的上下文，
                // 弹窗里拉完没地方放（只会静默改一个用户看不到的池子）。
                if (ui.engine != AppSettings.ENGINE_CUSTOM) {
                    scope.launch { repo.refreshModels() }
                }
            },
            onSelect = {
                repo.selectModel(it)
                showModels = false
            },
            onOpenSettings = {
                showModels = false
                onOpenSettings()
            },
            onDismiss = { showModels = false }
        )
    }

    if (renameDialog) {
        var title by remember { mutableStateOf(ui.activeTitle) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名会话") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.activeId?.let { repo.renameConversation(it, title.trim()) }
                    renameDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("删除会话") },
            text = { Text("会话与它的云端沙箱都会被删除，已产出的文件也会一并释放。") },
            confirmButton = {
                TextButton(onClick = {
                    ui.activeId?.let { repo.deleteConversation(it); onBack() }
                    deleteDialog = false
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } }
        )
    }
}

/** 「显示更早的消息」入口：默认只渲染尾部若干轮，往上点一下再扩一页。 */
@Composable
private fun EarlierRow(hiddenTurns: Int, hiddenMessages: Int, onLoad: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onLoad)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("显示更早的 ${hiddenTurns} 轮对话", style = MaterialTheme.typography.labelMedium)
                Text(
                    "还有 $hiddenMessages 条消息未渲染，展开可继续往上翻",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AskCard(ask: AskQuestion, onSubmit: (AskAnswer) -> Unit) {
    val items = remember(ask) { ask.allItems }
    // 单问单选走「点一下就答」；多问或多选必须等提交，否则勾第一个就把答案发出去了。
    val quick = items.size == 1 && !items[0].multiSelect && items[0].options.isNotEmpty()
    val picks = remember(ask) {
        mutableStateListOf<Set<Int>>().apply { repeat(items.size) { add(emptySet()) } }
    }
    val customs = remember(ask) {
        mutableStateListOf<String>().apply { repeat(items.size) { add("") } }
    }

    fun answerOf(): AskAnswer = AskAnswer(
        items.mapIndexed { index, item ->
            val chosen = picks.getOrNull(index).orEmpty().sorted().mapNotNull { item.options.getOrNull(it) }
            AskPick(
                labels = chosen.map { it.label },
                values = chosen.map { it.value },
                custom = customs.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
            )
        }
    )

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                ask.header?.takeIf { it.isNotBlank() } ?: "云端需要你确认",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            items.forEachIndexed { index, item ->
                Text(
                    if (items.size > 1) "${index + 1}. ${item.question}" else item.question,
                    style = MaterialTheme.typography.bodyMedium
                )
                item.options.forEachIndexed { optionIndex, option ->
                    val selected = picks.getOrNull(index)?.contains(optionIndex) == true
                    AskOptionRow(
                        option = option,
                        selected = selected,
                        // null 表示快路径：不画单选圈，整行就是按钮。
                        control = if (quick) null else item.multiSelect,
                        onClick = {
                            when {
                                quick -> onSubmit(
                                    AskAnswer(listOf(AskPick(listOf(option.label), listOf(option.value))))
                                )
                                item.multiSelect -> picks[index] =
                                    if (selected) picks[index] - optionIndex else picks[index] + optionIndex
                                else -> picks[index] = setOf(optionIndex)
                            }
                        }
                    )
                }
                // 选项都不合适时，用户可以自己写。
                OutlinedTextField(
                    value = customs[index],
                    onValueChange = { customs[index] = it },
                    placeholder = {
                        Text("其他（自行输入）", style = MaterialTheme.typography.bodySmall)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 至少答一题就能提交：多问时用户可能只想回答其中一两个，
            // 未答的在 answerText 里会写成「未选择」，不必强制每题都填。
            val ready = items.indices.any { index ->
                picks.getOrNull(index)?.isNotEmpty() == true || customs.getOrNull(index)?.isNotBlank() == true
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        if (quick) {
                            val custom = customs.firstOrNull()?.trim().orEmpty()
                            if (custom.isNotBlank()) onSubmit(AskAnswer(listOf(AskPick(custom = custom))))
                        } else if (ready) {
                            onSubmit(answerOf())
                        }
                    },
                    enabled = ready
                ) { Text(if (quick) "发送" else "提交") }
            }
        }
    }
}

/**
 * 提问卡片里的一个选项。`control` 为 null 时不画选择控件（点整行即答），
 * true 画多选框，false 画单选圈。
 */
@Composable
private fun AskOptionRow(option: AskOption, selected: Boolean, control: Boolean?, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (control) {
                true -> Checkbox(checked = selected, onCheckedChange = { onClick() })
                false -> RadioButton(selected = selected, onClick = onClick)
                null -> Unit
            }
            if (control != null) Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                option.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    uploading: Boolean,
    model: String,
    running: Boolean,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelClick: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    enterSends: Boolean = true
) {
    val enabled = value.isNotBlank() || attachments.isNotEmpty()
    val containerShape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (attachments.isNotEmpty() || uploading) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(attachments) { attachment ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                attachment.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.width(120.dp),
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { onRemoveAttachment(attachment.name) },
                                modifier = Modifier.size(22.dp)
                            ) { Icon(Icons.Filled.Close, "移除", Modifier.size(13.dp)) }
                        }
                    }
                }
                if (uploading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.6.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("读取文件…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // 抄 rikkahub 的输入容器：圆角 Surface + 细边框，输入框透明无下划线，底部一行工具按钮 + 圆形发送键。
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(containerShape),
            shape = containerShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // 回车发送可关：关闭后 ImeAction.Default，回车在多行输入框里就是换行。
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (enterSends) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = if (enterSends) {
                            KeyboardActions(onSend = { if (enabled) onSend() })
                        } else {
                            KeyboardActions.Default
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp, max = 160.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                    if (value.isEmpty()) {
                        Text(
                            text = "发消息给 WorkBuddy…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 附件入口收成一个按钮 + 二级菜单（照 RikkaHub 的添加弹出）：
                    // 两个图标挨着太容易点错。菜单里分「图片」与「文件」——图片
                    // 只出 image/*（读图模型能直发），文件出任意类型（进沙箱用工具读）。
                    var attachMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Filled.AttachFile,
                                "添加附件",
                                Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenu,
                            onDismissRequest = { attachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("图片") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Image, null, Modifier.size(18.dp))
                                },
                                onClick = {
                                    attachMenu = false
                                    onPickImage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("文件") },
                                leadingIcon = {
                                    Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(18.dp))
                                },
                                onClick = {
                                    attachMenu = false
                                    onPickFile()
                                }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable(onClick = onModelClick)
                    ) {
                        Text(
                            model.ifBlank { "默认模型" },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        val stop = running
                        val bg = when {
                            stop -> MaterialTheme.colorScheme.errorContainer
                            enabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val fg = when {
                            stop -> MaterialTheme.colorScheme.onErrorContainer
                            enabled -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .clickable(enabled = stop || enabled) { if (stop) onStop() else onSend() }
                        ) {
                            Icon(
                                imageVector = if (stop) Icons.Filled.Stop else Icons.Filled.KeyboardArrowUp,
                                contentDescription = if (stop) "停止" else "发送",
                                modifier = Modifier.size(20.dp),
                                tint = fg
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelDialog(
    /** 这条会话的类型：[AppSettings.ENGINE_WB] 只显示云端模型，[AppSettings.ENGINE_CUSTOM] 只显示本机模型。 */
    kind: String,
    models: List<cn.cloudbuddy.app.data.ModelChoice>,
    customModels: List<cn.cloudbuddy.app.data.CustomModel>,
    /** 本机模型的提供商清单：列表按提供商分组、条目显示提供商名。 */
    providers: List<cn.cloudbuddy.app.data.LlmProvider> = emptyList(),
    selected: String,
    loading: Boolean,
    error: String?,
    /** 本机端点的展示串（如 `api.deepseek.com`），让用户确认选的是哪套配置。 */
    endpointHint: String = "",
    /** 草稿会话才允许在这里改类型；会话建出来之后类型固定。 */
    canSwitchKind: Boolean = false,
    onSwitchKind: (String) -> Unit = {},
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val local = kind == cn.cloudbuddy.app.data.AppSettings.ENGINE_CUSTOM
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (local) "本机模型" else "云端模型", modifier = Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 类型只在草稿态可改：会话一旦建出来，两套历史就对不上了，
                // 强行切换会出现「前半段在云端、后半段在本机」的缝合会话。
                if (canSwitchKind) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            cn.cloudbuddy.app.data.AppSettings.ENGINE_WB to "云端",
                            cn.cloudbuddy.app.data.AppSettings.ENGINE_CUSTOM to "本机 API"
                        ).forEach { (value, label) ->
                            val active = kind == value
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { if (!active) onSwitchKind(value) }
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
                Text(
                    if (local) {
                        "配置：" + endpointHint.ifBlank { "未填接口地址" }
                    } else {
                        "云端会话只能用云端模型，选择会同步到账号（网页端也能看到）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (local) {
                    when {
                        loading && customModels.isEmpty() -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在拉取模型列表…", style = MaterialTheme.typography.bodySmall)
                        }

                        customModels.isEmpty() -> Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Text(
                                "还没有可用的本机模型。去设置里「拉取」一次端点清单，" +
                                    "勾几个加进「我的模型」，这里就能直接点选。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = onOpenSettings) { Text("去设置里添加") }
                        }

                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 按提供商分组（照 RikkaHub 的 ModelListSheet）：多端点时
                            // 用户要一眼看出某个模型挂在哪个端点下。
                            val grouped = customModels.groupBy { it.providerId }
                            grouped.forEach { (pid, group) ->
                                val providerName = providers.firstOrNull { it.id == pid }?.label
                                if (providers.size > 1 && providerName != null) {
                                    item(key = "header-$pid") {
                                        Text(
                                            providerName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                                items(group, key = { it.selKey }) { m ->
                                    ModelOption(m, providers, selected, onSelect)
                                }
                            }
                        }
                    }
                } else {
                    when {
                        loading && models.isEmpty() -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在从云端获取模型…", style = MaterialTheme.typography.bodySmall)
                        }

                        models.isEmpty() -> Text(
                            "暂时没有拿到模型列表，点下面的「重新加载」再试一次。",
                            style = MaterialTheme.typography.bodySmall
                        )

                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(models, key = { it.id }) { model ->
                                val active = model.id == selected
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth().clickable { onSelect(model.id) }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                model.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            model.credits?.let {
                                                Text(
                                                    "x$it",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Text(
                                            model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        model.description?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            // 本机的模型清单在设置页维护（那里才有填写与拉取的上下文），
            // 弹窗里只负责「点一个用哪个」。
            TextButton(onClick = if (local) onOpenSettings else onRefresh) {
                Text(if (local) "去设置里拉取" else "重新加载")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 从 SAF 读取附件内容，限制单文件 24MB（更大的文件请放进沙箱或改用工作区上传）。 */
private fun readAttachment(context: android.content.Context, uri: Uri): Result<PendingAttachment> = runCatching {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: "attachment"
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("无法读取该文件")
    require(bytes.size <= 24 * 1024 * 1024) { "文件超过 24MB，请改用工作区上传" }
    PendingAttachment(name, mime, bytes)
}

/**
 * 本机模型弹窗里的一个条目。
 *
 * 选中值与回调都用选择键（providerId/modelId）：不同提供商下的同名模型
 * 是两条不同配置，用裸 ID 会互相顶掉。
 */
@Composable
private fun ModelOption(
    m: cn.cloudbuddy.app.data.CustomModel,
    providers: List<cn.cloudbuddy.app.data.LlmProvider>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val active = m.selKey == selected
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { onSelect(m.selKey) }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 显示名是用户自己起的（「DeepSeek 便宜那个」），
                // 比裸 ID 好认；ID 与提供商保留成小字，出问题时对得上日志。
                Text(m.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        if (m.title != m.id) append(m.id)
                        if (m.title == m.id && providers.size > 1) {
                            providers.firstOrNull { it.id == m.providerId }?.let { append(it.label) }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                // 能力指示（照 RikkaHub 的模型卡标签）：模态 / 工具 / 思考 / 上下文
                cn.cloudbuddy.app.ui.components.ModelAbilityTags(
                    vision = m.vision,
                    tools = m.tools,
                    reasoning = m.reasoning,
                    contextTokens = cn.cloudbuddy.app.data.ModelRegistry.find(m.id)?.contextLength,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (active) {
                Text(
                    "使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
