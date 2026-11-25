package cn.cloudbuddy.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cloudbuddy.app.data.Ansi
import cn.cloudbuddy.app.data.AnsiColor
import cn.cloudbuddy.app.data.CmdEntry
import cn.cloudbuddy.app.data.CmdSource
import cn.cloudbuddy.app.data.TerminalState
import cn.cloudbuddy.app.data.TerminalTab
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 命令行窗口。
 *
 * 为什么要有这个页面：自定义引擎下推理在本机、执行在云端沙箱，中间隔着一层模型。
 * 模型说「我已经装好了」「文件已改」，用户没有任何独立渠道去核实——只能选择信或不信。
 * 有了终端，`ls` 一眼就知道真假，也能自己接手排查。
 *
 * 交互照手机终端的通行做法来（Termux / ConnectBot 那一套）：输入框带**光标位置**，
 * 上面有一排快捷符号键（手机上敲 `/`、`|`、`$` 要切两次键盘，这是最大的摩擦），
 * `↑`/`↓` 双向翻历史并保留未提交的草稿，长输出默认只露尾部、可展开。
 *
 * 无论明暗主题一律深色：终端就该是深色的，跟着主题变浅反而显得不像终端，
 * 而且等宽白字压在浅色底上非常刺眼。
 */
private object Term {
    val bg = Color(0xFF0D1117)
    val bar = Color(0xFF161B22)
    val line = Color(0xFF21262D)
    val text = Color(0xFFC9D1D9)
    val dim = Color(0xFF8B949E)
    val green = Color(0xFF3FB950)
    val red = Color(0xFFFF7B72)
    val amber = Color(0xFFD29922)
    val cyan = Color(0xFF56D4DD)
    val cursor = Color(0xFF58A6FF)
    val key = Color(0xFF30363D)
}

/** 折叠阈值：超过这么多行就先只显示尾部。agent 一次 `cat` 就能刷几百行。 */
private const val COLLAPSE_LINES = 18

/** 展开一次多给多少行。400 行一屏左右，不会一次布局几千行。 */
private const val PAGE_LINES = 400

/** 实时输出只渲染尾部这么多字符，避免每帧排版整段大文本。 */
private const val LIVE_TAIL_CHARS = 6_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalState,
    title: String,
    available: Boolean,
    /** 终端字号（sp），由设置持久化。 */
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onRenameTab: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    val mono = FontFamily.Monospace
    // 每个标签一份滚动位置：切回来还停在上次看的地方，而不是被拽到底。
    val listStates = remember { mutableMapOf<Long, LazyListState>() }
    val listState = listStates.getOrPut(state.activeTabId) { LazyListState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // 历史游标：-1 表示「正在输入的不是历史」，0 是最近一条。history 新的在前。
    var histIndex by remember { mutableStateOf(-1) }
    // 翻历史前把用户已经敲了一半的内容存起来，↓ 回到末尾时要还给他。
    var draft by remember { mutableStateOf("") }
    var fontMenu by remember { mutableStateOf(false) }

    val sp = fontSize.coerceIn(10, 20).sp
    val editable = available && !state.running

    val submit = {
        val cmd = input.text
        if (cmd.isNotBlank() && editable) {
            onRun(cmd)
            input = TextFieldValue("")
            histIndex = -1
            draft = ""
        }
    }

    /** 在光标处插入文字（快捷符号键用）。 */
    fun insert(text: String) {
        val sel = input.selection
        val newText = input.text.replaceRange(sel.start, sel.end, text)
        input = TextFieldValue(newText, TextRange(sel.start + text.length))
    }

    fun backspace() {
        val sel = input.selection
        if (sel.start != sel.end) {
            input = TextFieldValue(
                input.text.removeRange(sel.start, sel.end),
                TextRange(sel.start)
            )
        } else if (sel.start > 0) {
            input = TextFieldValue(
                input.text.removeRange(sel.start - 1, sel.start),
                TextRange(sel.start - 1)
            )
        }
    }

    fun historyPrev() {
        val h = state.history
        if (h.isEmpty()) return
        if (histIndex == -1) draft = input.text
        val next = if (histIndex == -1) 0 else (histIndex + 1).coerceAtMost(h.lastIndex)
        histIndex = next
        input = TextFieldValue(h[next], TextRange(h[next].length))
    }

    fun historyNext() {
        val h = state.history
        if (h.isEmpty() || histIndex == -1) return
        if (histIndex == 0) {
            histIndex = -1
            input = TextFieldValue(draft, TextRange(draft.length))
        } else {
            histIndex -= 1
            input = TextFieldValue(h[histIndex], TextRange(h[histIndex].length))
        }
    }

    // 是否跟着最新输出走。
    //
    // 只在**用户手指离开屏幕那一刻**重新判定：输出自己在变长时 canScrollForward
    // 也会变成 true（下面确实还有内容），但那不代表用户想停在上方。拿几何状态
    // 实时判定，结果是"新内容一进来就停止跟随"——现象就是停在输出开头不再往下走。
    var follow by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            // 手势结束后仍停在倒数两项之外，才认定为"在看历史"。
            // 只按 canScrollForward 判会把"随手碰一下屏幕"也算成上翻。
            follow = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(listState, state.activeTabId, state.entries.size, state.liveOutput.length, state.running) {
        if (!follow) return@LaunchedEffect
        // 等一帧让布局跟上内容，再滚到"当前真实的最后一项"。
        // 直接 scrollToItem(Int.MAX_VALUE) 在条目数还没更新时会被 coerce 到 0，
        // 表现就是输出一来页面跳回这段的开头。
        withFrameNanos { }
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) runCatching { listState.scrollToItem(last) }
    }

    Scaffold(
        containerColor = Term.bg,
        topBar = {
            Column(modifier = Modifier.background(Term.bar)) {
                TopAppBar(
                    title = {
                        Column {
                            Text("终端", color = Term.text, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (available) title.ifBlank { "沙箱" } + " · /workspace"
                                else "先进入一个会话",
                                color = Term.dim,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Term.text)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { fontMenu = true }) {
                                Icon(Icons.Filled.FormatSize, "字号", tint = Term.dim)
                            }
                            DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                                listOf(12 to "小", 14 to "中", 16 to "大", 18 to "特大").forEach { (v, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "$label（$v）",
                                                color = if (v == fontSize) Term.cyan else Term.text
                                            )
                                        },
                                        onClick = {
                                            onFontSize(v)
                                            fontMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            val all = state.entries.joinToString("\n\n") { it.toPlainText() }
                            if (all.isNotBlank()) clipboard.setText(AnnotatedString(all))
                        }) {
                            Icon(Icons.Filled.ContentCopy, "复制全部", tint = Term.dim)
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.DeleteSweep, "清屏", tint = Term.dim)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Term.bar)
                )
                if (state.tabs.isNotEmpty()) {
                    TabStrip(
                        tabs = state.tabs,
                        activeId = state.activeTabId,
                        onSelect = onSelectTab,
                        onNew = onNewTab,
                        onClose = onCloseTab,
                        onRename = onRenameTab,
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Term.bg)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.entries.isEmpty() && state.liveOutput.isEmpty() && !state.running) {
                        item {
                            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "$ 输入命令，直接在这个会话的沙箱里执行",
                                    color = Term.dim, fontFamily = mono, fontSize = 12.sp
                                )
                                Text(
                                    "不经过模型、不消耗积分。agent 跑过的命令也会出现在这里。",
                                    color = Term.dim, fontFamily = mono, fontSize = 11.sp
                                )
                                Text(
                                    "走 sh -c，不支持交互式命令（vim / top / 需要输入密码的 sudo）。",
                                    color = Term.dim, fontFamily = mono, fontSize = 11.sp
                                )
                                Text(
                                    "长按输出可选中复制；命令多行时点右上角「复制全部」。",
                                    color = Term.dim, fontFamily = mono, fontSize = 11.sp
                                )
                            }
                        }
                    }

                    items(state.entries, key = { it.id }) { entry ->
                        EntryBlock(entry = entry, fontSize = sp)
                    }

                    if (state.running) {
                        item {
                            // 和正文条目同构（左侧竖线 + 相同缩进）：按停之后它会变成一条
                            // 正式记录，样式一致才不会看起来像"东西没了、换了一块"。
                            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                Box(
                                    Modifier
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(Term.green.copy(alpha = 0.45f))
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "$ " + (state.runningCommand ?: ""),
                                        color = Term.cursor, fontFamily = mono, fontSize = sp
                                    )
                                    if (state.liveOutput.isNotEmpty()) {
                                        AnsiTextBlock(
                                            // 只画尾部：一段 npm install 的实时输出可能几十万字，
                                            // 每来一帧就排版全文会让整页掉到个位数帧率。
                                            text = state.liveOutput.takeLast(LIVE_TAIL_CHARS),
                                            fontSize = sp
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(10.dp),
                                            strokeWidth = 1.4.dp,
                                            color = Term.amber
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "执行中…",
                                            color = Term.amber, fontFamily = mono, fontSize = 11.sp
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            // 说明白"按停不会丢输出"：之前按停等于清屏，
                                            // 用户吃过一次亏就会不敢按。
                                            "按停会保留已收到的输出",
                                            color = Term.dim, fontFamily = mono, fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 上翻看历史时，新输出不能把人拽走，但得让人知道「有新的」并且一键回去。
                if (!follow) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Term.key,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .clickable {
                                follow = true
                                scope.launch {
                                    val last = listState.layoutInfo.totalItemsCount - 1
                                    if (last >= 0) runCatching { listState.animateScrollToItem(last) }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Term.cyan
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("最新", color = Term.cyan, fontSize = 11.sp, fontFamily = mono)
                        }
                    }
                }
            }

            // ---------- 快捷符号键（手机上敲 / | $ 要切两次键盘） ----------
            Surface(color = Term.bar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Term.line))

                    if (state.notice != null) {
                        Text(
                            state.notice,
                            color = Term.amber,
                            fontFamily = mono,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 功能键固定在左边：历史、退格是高频操作。
                        KeyCap("↑", enabled = state.history.isNotEmpty() && !state.running) { historyPrev() }
                        KeyCap("↓", enabled = state.history.isNotEmpty() && !state.running) { historyNext() }
                        KeyCap("⌫", enabled = editable) { backspace() }
                        listOf("/", "-", "_", ".", "|", ">", "&", "$", "*", "\"", "'", "~", "#", "=", ":").forEach {
                            KeyCap(it, enabled = editable) { insert(it) }
                        }
                    }

                    // ---------- 输入区 ----------
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "$",
                            color = Term.green,
                            fontFamily = mono,
                            fontSize = sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        Box(Modifier.weight(1f)) {
                            if (input.text.isEmpty()) {
                                Text(
                                    if (available) "输入命令…" else "先进入一个会话",
                                    color = Term.dim.copy(alpha = 0.6f),
                                    fontFamily = mono,
                                    fontSize = sp
                                )
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = {
                                    input = it
                                    // 手改了就脱离历史，否则 ↑↓ 会接着上次的位置跳。
                                    if (histIndex != -1) histIndex = -1
                                },
                                enabled = editable,
                                textStyle = LocalTextStyle.current.copy(
                                    color = Term.text,
                                    fontFamily = mono,
                                    fontSize = sp
                                ),
                                cursorBrush = SolidColor(Term.cursor),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Send
                                ),
                                keyboardActions = KeyboardActions(onSend = { submit() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (state.running) {
                            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Stop, "中断", tint = Term.red)
                            }
                        } else {
                            IconButton(
                                onClick = { submit() },
                                enabled = available && input.text.isNotBlank(),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    "执行",
                                    tint = if (available && input.text.isNotBlank()) Term.green else Term.line
                                )
                            }
                        }
                    }

                    if (state.history.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                        ) {
                            state.history.take(4).forEach { h ->
                                Text(
                                    h.lineSequence().first().take(28),
                                    color = Term.dim,
                                    fontFamily = mono,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .background(Term.key, RoundedCornerShape(50))
                                        .clickable {
                                            input = TextFieldValue(h, TextRange(h.length))
                                            histIndex = -1
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 标签条。
 *
 * 手机上标签标题的空间就这么点，靠自动编号区分不了"哪个在跑 install"，
 * 所以：运行中的标签带点、长按可以改名、只有多于一个标签时才出现关闭按钮
 * （只剩一个的时候那个 × 没有任何意义，还容易误触）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabStrip(
    tabs: List<TerminalTab>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onClose: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
) {
    var renaming by remember { mutableStateOf<TerminalTab?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val active = tab.id == activeId
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (active) Term.key else Color.Transparent,
                modifier = Modifier.combinedClickable(
                    onClick = { onSelect(tab.id) },
                    onLongClick = { renaming = tab },
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        start = 9.dp,
                        end = if (tabs.size > 1) 3.dp else 9.dp,
                        top = 5.dp,
                        bottom = 5.dp
                    )
                ) {
                    if (tab.running) {
                        // 切到别的标签后，这边还在跑——没这个点就完全看不出来。
                        Box(Modifier.size(6.dp).background(Term.amber, CircleShape))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        tab.title,
                        color = if (active) Term.text else Term.dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    if (tabs.size > 1) {
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                "关闭这个终端",
                                modifier = Modifier.size(11.dp),
                                tint = Term.dim
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onNew, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Filled.Add, "新建终端", modifier = Modifier.size(15.dp), tint = Term.cyan)
        }
    }

    renaming?.let { tab ->
        var name by remember(tab.id) { mutableStateOf(tab.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名终端") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("标题") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(tab.id, name)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            }
        )
    }
}

/** 一个快捷键。按下没反应时（比如执行中）保持暗色，避免用户以为是坏了。 */
@Composable
private fun KeyCap(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Term.key,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            label,
            color = if (enabled) Term.text else Term.dim.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

/**
 * 按 ANSI 颜色渲染输出。
*
 * 命令输出里带 `\e[32m` 这类转义是常态（`ls --color`、`git status`、`npm` 全都带），
 * 原样显示就是一串 `[32m` 方括号，看着像终端坏了。这里认识 16 色并按色画，
 * 其余控制序列（清行、移光标）丢掉——静态输出里它们没有意义。
 */
@Composable
private fun AnsiTextBlock(text: String, fontSize: TextUnit, fallback: Color = Term.text) {
    val styled = remember(text) {
        // 纯文本直接画，不建 AnnotatedString：实时输出刷新最频繁，这一层能省就省。
        if (text.indexOf('\u001B') < 0 && text.indexOf('\r') < 0) {
            null
        } else {
            buildAnnotatedString {
                Ansi.spans(text).forEach { span ->
                    withStyle(
                        SpanStyle(
                            color = ansiColorOf(span.color),
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                        )
                    ) { append(span.text) }
                }
            }
        }
    }
    if (styled != null) {
        Text(styled, color = fallback, fontFamily = FontFamily.Monospace, fontSize = fontSize)
    } else {
        Text(text, color = fallback, fontFamily = FontFamily.Monospace, fontSize = fontSize)
    }
}

/** 16 色映射到调色板；`Color.Unspecified` 表示跟随外层默认色。 */
private fun ansiColorOf(c: AnsiColor?): Color = when (c) {
    null -> Color.Unspecified
    AnsiColor.BLACK, AnsiColor.GRAY -> Term.dim
    AnsiColor.RED, AnsiColor.BRIGHT_RED -> Term.red
    AnsiColor.GREEN, AnsiColor.BRIGHT_GREEN -> Term.green
    AnsiColor.YELLOW, AnsiColor.BRIGHT_YELLOW -> Term.amber
    AnsiColor.BLUE, AnsiColor.BRIGHT_BLUE -> Term.cursor
    AnsiColor.MAGENTA, AnsiColor.BRIGHT_MAGENTA -> Color(0xFFD2A8FF)
    AnsiColor.CYAN, AnsiColor.BRIGHT_CYAN -> Term.cyan
    AnsiColor.WHITE, AnsiColor.BRIGHT_WHITE -> Term.text
}

/** 一条命令的展示：命令原文 + stdout + stderr + 退出码。长输出默认只露尾部。 */
@Composable
private fun EntryBlock(entry: CmdEntry, fontSize: androidx.compose.ui.unit.TextUnit) {
    val mono = FontFamily.Monospace
    val clipboard = LocalClipboardManager.current

    // 展开按页来，不一次铺开：`cat` 一个几万行的日志，整段 Text 一次布局会卡死，
    // 而用户通常只想往上翻几屏。每点一次多给一页。
    var extra by remember(entry.id) { mutableStateOf(0) }
    val outLines = remember(entry.stdout) { entry.stdout.lineSequence().count() }
    val hidden = (outLines - COLLAPSE_LINES - extra).coerceAtLeast(0)
    val shownOut = remember(entry.stdout, hidden) {
        if (hidden <= 0) entry.stdout
        else entry.stdout.lineSequence().drop(hidden).joinToString("\n")
    }

    SelectionContainer {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧竖线：一眼分清一条命令从哪开始、到哪结束，比空行可靠。
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(if (entry.source == CmdSource.AGENT) Term.cyan.copy(alpha = 0.45f) else Term.green.copy(alpha = 0.45f))
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        if (entry.source == CmdSource.AGENT) "agent $" else "$",
                        color = if (entry.source == CmdSource.AGENT) Term.cyan else Term.green,
                        fontFamily = mono,
                        fontSize = fontSize
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        entry.command,
                        color = Term.text,
                        fontFamily = mono,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(entry.toPlainText())) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            "复制这条命令",
                            modifier = Modifier.size(13.dp),
                            tint = Term.dim.copy(alpha = 0.7f)
                        )
                    }
                }

                if (hidden > 0) {
                    Text(
                        "… 上方还有 $hidden 行，点此展开 ${minOf(hidden, PAGE_LINES)} 行",
                        color = Term.cyan,
                        fontFamily = mono,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { extra += PAGE_LINES }
                            .padding(vertical = 3.dp)
                    )
                }
                if (shownOut.isNotEmpty()) {
                    AnsiTextBlock(text = shownOut, fontSize = fontSize)
                }
                if (hidden == 0 && extra > 0) {
                    Text(
                        "收起（回到尾部 $COLLAPSE_LINES 行）",
                        color = Term.dim,
                        fontFamily = mono,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { extra = 0 }
                            .padding(vertical = 3.dp)
                    )
                }
                if (entry.stderr.isNotEmpty()) {
                    // stderr 默认走琥珀色，但输出里自己带颜色时以它为准——
                    // `ls` 把错误行标红是有意义的，统一刷成黄反而丢了信息。
                    AnsiTextBlock(text = entry.stderr, fontSize = fontSize, fallback = Term.amber)
                }
                if (entry.error != null) {
                    Text("[error] " + entry.error, color = Term.red, fontFamily = mono, fontSize = fontSize)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.aborted) {
                        // 中断不是失败：标红会让人以为命令出错了，实际是「我让它停的」。
                        Text(
                            "已中断",
                            color = Term.amber,
                            fontFamily = mono,
                            fontSize = 10.sp
                        )
                    } else {
                        Text(
                            "exit " + (entry.exitCode?.toString() ?: "?"),
                            color = if (entry.ok) Term.green else Term.red,
                            fontFamily = mono,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.durationMs.toString() + "ms",
                        color = Term.dim,
                        fontFamily = mono,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timeOf(entry.startedAt),
                        color = Term.dim.copy(alpha = 0.7f),
                        fontFamily = mono,
                        fontSize = 10.sp
                    )
                    if (entry.truncated) {
                        Spacer(Modifier.width(8.dp))
                        Text("已截断", color = Term.amber, fontFamily = mono, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** 时间只到分秒：终端里看的是「这条是不是刚才那条」，精确到毫秒没有意义。 */
private fun timeOf(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
