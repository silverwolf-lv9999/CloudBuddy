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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.Conversation
import cn.cloudbuddy.app.data.TurnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    conversations: List<Conversation>,
    nickname: String,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRefresh: suspend () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    /** 强制解除某条会话卡住的运行状态（云端状态位滞留时的手动自救入口）。 */
    onResetRun: (String) -> Unit = {},
    /** 顶部副标题里的余额提示（如「128 积分」），为空则不显示。 */
    balanceLabel: String = "",
    /** 启动时连不上服务器：空列表不代表会话没了，别让用户以为记录丢了。 */
    offline: Boolean = false,
    /**
     * 属于「本机 API」的会话 id。
     *
     * 两套会话并存但互不相通：云端会话扣积分、在服务端跑；本机会话用用户自己的
     * Key、推理在手机上。列表里不标出来，用户根本分不清哪条是哪条。
     */
    localIds: Set<String> = emptySet()
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    val scope = rememberCoroutineScope()

    // 30 秒一次的后台对齐（仓库里列表没变化会跳过状态写入，不再带动整页重组）。
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(30_000)
        }
    }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("云端会话", style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOf(
                                nickname.ifBlank { "WorkBuddy" },
                                "云沙箱",
                                balanceLabel
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                onRefresh()
                                refreshing = false
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("新建会话") }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索会话标题") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            if (offline) "当前网络不可用" else "还没有会话",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (offline) {
                                "登录状态还在，联网后下拉刷新就能看到你的会话。"
                            } else {
                                "新建一次对话，云端会在独立沙箱里准备 /workspace，\nagent 就在那里读写文件、执行命令。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }, contentType = { "conv" }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            local = conversation.id in localIds,
                            onOpen = { onOpen(conversation.id) },
                            onRename = { renameTarget = conversation },
                            onDelete = { deleteTarget = conversation },
                            onResetRun = { onResetRun(conversation.id) }
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) onRename(target.id, title.trim())
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("「${target.title}」将从云端删除，沙箱工作区也会一并释放。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    local: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onResetRun: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    // 与设置页 SettingCard 同款：无阴影 Surface（阴影滚动时每帧重绘脏区，是卡顿
    // 主因）、18dp 圆角；行结构同 SettingRow——标题 + 小字摘要，不再用图标和
    // 彩色徽章，主页与设置页一个观感。
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 类型 · 状态 · 产物，一行小字看完；已完成/空闲不再标状态，
                    // 默认状态不上屏，扫一眼只剩有事的会话在动。
                    listOfNotNull(
                        if (local) "本机" else "云端",
                        when (conversation.state) {
                            TurnState.RUNNING -> "执行中"
                            TurnState.WAITING -> "等待回复"
                            TurnState.FAILED -> "执行失败"
                            TurnState.STOPPED -> "已停止"
                            else -> null
                        },
                        conversation.artifacts.takeIf { it.isNotEmpty() }?.let { "${it.size} 个产物" },
                        conversation.updatedAt.takeIf { it > 0 }?.let { relativeTime(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, "更多", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onRename() }
                    )
                    // 只在「显示运行中」时出现：这就是用户被锁住的那种会话，
                    // 给他一条不必新建会话的自救路。
                    if (conversation.state == TurnState.RUNNING ||
                        conversation.state == TurnState.WAITING
                    ) {
                        DropdownMenuItem(
                            text = { Text("解除卡住的运行状态") },
                            leadingIcon = {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            },
                            onClick = { menu = false; onResetRun() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** 只在主线程的组合里调用，共享一个 formatter 即可，免得每张卡都 new 一个。 */
private val dayFormat = SimpleDateFormat("M月d日", Locale.CHINA)

fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> dayFormat.format(Date(timestamp))
    }
}
