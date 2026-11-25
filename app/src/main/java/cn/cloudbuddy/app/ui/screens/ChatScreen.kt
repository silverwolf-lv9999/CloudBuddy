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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.AskAnswer
import cn.cloudbuddy.app.data.AskOption
import cn.cloudbuddy.app.data.AskPick
import cn.cloudbuddy.app.data.AskQuestion
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.PendingAttachment
import cn.cloudbuddy.app.data.TurnState
import cn.cloudbuddy.app.ui.components.ChatItem
import cn.cloudbuddy.app.ui.components.MessageRow
import cn.cloudbuddy.app.ui.components.StatePill
import cn.cloudbuddy.app.ui.components.StepsGroup
import cn.cloudbuddy.app.ui.components.windowTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    Surface(
                        onClick = { renameDialog = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                ui.activeTitle.ifBlank { if (ui.draft) "新会话" else "云端会话" },
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (ui.loadingConversation) {
                                    "同步云端历史…"
                                } else {
                                    ui.models.firstOrNull { it.id == ui.selectedModel }?.name
                                        ?: ui.selectedModel.ifBlank { "云端沙箱" }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            StatePill(ui.state, Modifier.padding(end = 2.dp))
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.MoreVert, "更多", modifier = Modifier.size(20.dp))
                                }
                                DropdownMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    shape = MaterialTheme.shapes.largeIncreased
                                ) {
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
                                                onCheckedChange = null,
                                                modifier = Modifier.scale(0.8f)
                                            )
                                        },
                                        onClick = { repo.setCollapseSteps(!ui.stepsCollapsed) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("沙箱工作区") },
                                        leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                                        onClick = { menu = false; onOpenWorkspace() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("终端") },
                                        leadingIcon = { Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp)) },
                                        onClick = { menu = false; onOpenTerminal() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("选择模型") },
                                        leadingIcon = { Icon(Icons.Filled.Cloud, null, Modifier.size(18.dp)) },
                                        onClick = {
                                            menu = false
                                            showModels = true
                                            scope.launch { repo.refreshModels() }
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
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding()
            )
        },
        bottomBar = {
            Composer(
                value = input,
                onValueChange = { input = it },
                attachments = attachments,
                uploading = uploading,
                model = ui.models.firstOrNull { it.id == ui.selectedModel }?.name ?: ui.selectedModel,
                running = ui.state == TurnState.RUNNING || ui.sending,
                enterSends = repo.settings.enterSend,
                onPickFile = { picker.launch(arrayOf("*/*")) },
                onRemoveAttachment = { name -> attachments = attachments.filterNot { it.name == name } },
                onModelClick = {
                    showModels = true
                    scope.launch { repo.refreshModels() }
                },
                onStop = { repo.stop() },
                onSend = {
                    val text = input.trim()
                    if (text.isEmpty() && attachments.isEmpty()) return@Composer
                    repo.send(text, attachments)
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
                    // 有本地缓存时不要整屏盖住：直接显示缓存内容，加载态只体现在顶栏副标题里。
                    // 以前 loadingConversation 一律走全屏 loading，缓存等于白读。
                    ui.loadingConversation && ui.messages.isEmpty() -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ContainedLoadingIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.height(16.dp))
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    ui.messages.isEmpty() -> Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Filled.Cloud,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "在云端沙箱里开始任务",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "任务在官方云端环境执行，产出的文件会出现在沙箱 /workspace 里，" +
                                "可以直接在「工作区」中浏览、预览和取回。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        // 这里不要再放全宽色块（曾经的 2dp LinearProgressIndicator 带浅灰 track）。
                        // 它紧贴标题胶囊下沿，视觉上就是一条挡内容的灰条。加载态改到顶栏副标题。
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                if (item is ChatItem.Steps) "steps" else "single"
                            }) { item ->
                                when (item) {
                                    is ChatItem.Single -> MessageRow(
                                        message = item.message,
                                        showThoughts = repo.settings.showThoughts
                                    )

                                is ChatItem.Steps -> {
                                    // 默认展开规则：只有「正在跑的那一轮」展开，历史轮次按全局开关折叠。
                                    // 之前用全局 running 判断，导致任意一轮在跑时，所有历史折叠条
                                    // 都被强行展开，看起来就是「每一个都张开了」。
                                    val lastTurn = ui.messages.lastOrNull()?.turn
                                    val groupTurn = item.steps.lastOrNull()?.turn
                                    val runningThisTurn = (ui.state == TurnState.RUNNING || ui.sending) &&
                                        groupTurn != null && groupTurn == lastTurn
                                    val expanded = ui.stepsExpanded[item.key]
                                        ?: (if (runningThisTurn) true else !ui.stepsCollapsed)
                                    StepsGroup(
                                        steps = item.steps,
                                        expanded = expanded,
                                        state = ui.state,
                                        showThoughts = repo.settings.showThoughts,
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
                            if (ui.sending || ui.state == TurnState.RUNNING) {
                                item("sending") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                            item("tail") { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }

            ui.historyNotice?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            notice,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
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
                    shape = MaterialTheme.shapes.largeIncreased,
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
            models = ui.models,
            selected = ui.selectedModel,
            loading = ui.modelsLoading,
            error = ui.modelError,
            onRefresh = { scope.launch { repo.refreshModels() } },
            onSelect = {
                repo.selectModel(it)
                showModels = false
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
                OutlinedTextField(
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
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                Text(
                    "显示更早的 ${hiddenTurns} 轮对话",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "还有 $hiddenMessages 条消息未渲染，展开可继续往上翻",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AskCard(ask: AskQuestion, onSubmit: (AskAnswer) -> Unit) {
    val items = remember(ask) { ask.allItems }
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
        shape = MaterialTheme.shapes.extraLargeIncreased,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                ask.header?.takeIf { it.isNotBlank() } ?: "云端需要你确认",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            items.forEachIndexed { index, item ->
                Text(
                    if (items.size > 1) "${index + 1}. ${item.question}" else item.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                item.options.forEachIndexed { optionIndex, option ->
                    val selected = picks.getOrNull(index)?.contains(optionIndex) == true
                    AskOptionRow(
                        option = option,
                        selected = selected,
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
                OutlinedTextField(
                    value = customs[index],
                    onValueChange = { customs[index] = it },
                    placeholder = {
                        Text("其他（自行输入）", style = MaterialTheme.typography.bodySmall)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

/** 提问卡片里的一个选项。`control` 为 null 时不画选择控件（点整行即答），true 画多选框，false 画单选圈。 */
@Composable
private fun AskOptionRow(option: AskOption, selected: Boolean, control: Boolean?, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
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
                Text(option.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                option.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 底部输入栏（对齐 RikkaHub 的 ChatInput）：
 * - 外层大圆角 Surface（shapes.largeIncreased）+ 1dp outlineVariant 边框 + surfaceContainerHigh 背景
 * - 内部纵向三段：附件行（可选）/ 文本输入 / 底部工具行（附件、模型选择、发送/停止）
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    uploading: Boolean,
    model: String,
    running: Boolean,
    onPickFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onModelClick: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    enterSends: Boolean = true
) {
    val enabled = value.isNotBlank() || attachments.isNotEmpty()
    val containerShape = MaterialTheme.shapes.largeIncreased
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
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                attachment.name,
                                style = MaterialTheme.typography.bodySmall,
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
                            ContainedLoadingIndicator(Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("读取文件…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(containerShape),
            shape = containerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    IconButton(onClick = onPickFile, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.AttachFile,
                            "添加附件",
                            Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelDialog(
    models: List<cn.cloudbuddy.app.data.ModelChoice>,
    selected: String,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var custom by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val filtered = remember(models, search) {
        if (search.isBlank()) models
        else models.filter {
            it.name.contains(search, ignoreCase = true) ||
                it.id.contains(search, ignoreCase = true) ||
                it.description?.contains(search, ignoreCase = true) == true
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        // 默认 contentWindowInsets 会把系统栏高度垫进 sheet 内部，容器色在内容下方
        // 多出一块不透明色条。置零后由内容自己用 navigationBarsPadding 控制间距。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "选择模型",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    modifier = Modifier.weight(1f)
                )
                if (loading) {
                    ContainedLoadingIndicator(modifier = Modifier.size(20.dp))
                }
            }

            Text(
                "当前：" + (models.firstOrNull { it.id == selected }?.name
                    ?: selected.ifBlank { "云端默认" }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索模型…") },
                shape = CircleShape,
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(20.dp)) }
            )

            when {
                loading && models.isEmpty() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 24.dp)
                ) {
                    ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("正在从云端获取模型…", style = MaterialTheme.typography.bodyMedium)
                }

                models.isEmpty() -> Text(
                    "暂时没有拿到模型列表，可以直接在下方填写模型 ID，或点「重新加载」。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { model ->
                        val active = model.id == selected
                        Surface(
                            shape = MaterialTheme.shapes.largeIncreased,
                            color = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(model.id)
                                dismiss()
                            }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        model.name,
                                        style = MaterialTheme.typography.titleSmallEmphasized,
                                        modifier = Modifier.weight(1f)
                                    )
                                    model.credits?.let {
                                        Text(
                                            "x$it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                model.description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Text(
                "云端未列出模型时，可手动填写模型 ID",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.largeIncreased
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = custom.isNotBlank(),
                    onClick = {
                        onSelect(custom.trim())
                        dismiss()
                    }
                ) { Text("使用") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRefresh) { Text("重新加载") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { dismiss() }) { Text("关闭") }
            }
        }
    }
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
